"""Isolated MySQL migration tests. Requires compose.sync-test.yml mysql; never uses application credentials."""
import contextlib
import copy
import io
import tempfile
import unittest
from pathlib import Path
from migrate_keycloak_accounts import database, migrate

PROPS = {"spring.datasource.url": "jdbc:mysql://127.0.0.1:13316/invitevip_sync_test",
         "spring.datasource.username": "root", "spring.datasource.password": "sync-test-only"}
MYSQL = "/usr/local/mysql/bin/mysql"


class FakeKeycloak:
    def __init__(self):
        self.user = {"id": "verified-id", "username": "superadmin", "enabled": True}
        self.configured = False
    def call(self, path, data=None):
        if path == "/users/profile":
            self.configured = True
            return None
        if path != "/users/verified-id":
            raise RuntimeError("Unknown user ID")
        return self.user
    def profile(self):
        return {}, not self.configured


class MigrationTests(unittest.TestCase):
    def setUp(self):
        with database(PROPS, MYSQL) as q:
            q("DROP DATABASE IF EXISTS invitevip_account_migration_test; CREATE DATABASE invitevip_account_migration_test;")
        props = copy.copy(PROPS)
        props["spring.datasource.url"] = "jdbc:mysql://127.0.0.1:13316/invitevip_account_migration_test"
        self.db = database(props, MYSQL)
        self.q = self.db.__enter__()
        self.q("""CREATE TABLE admin (id BIGINT PRIMARY KEY AUTO_INCREMENT, username VARCHAR(50) NOT NULL UNIQUE,
                name VARCHAR(30), role VARCHAR(30), keycloak_id VARCHAR(100));
                INSERT INTO admin (username,name,role,keycloak_id) VALUES ('superadmin','기존 이름','SUPER_ADMIN','verified-id'),
                ('legacy','미연결','ADMIN',NULL);""")
        self.kc = FakeKeycloak()
        self.temp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.db.__exit__(None, None, None)
        self.temp.cleanup()
        with database(PROPS, MYSQL) as q:
            q("DROP DATABASE invitevip_account_migration_test;")

    def run_migration(self, apply=True, query=None):
        with contextlib.redirect_stdout(io.StringIO()):
            migrate(query or self.q, self.kc, apply, Path(self.temp.name))

    def state_column_count(self):
        return self.q("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='admin' AND column_name='state';")

    def test_read_only_preflight_does_not_change_schema_or_profile(self):
        self.run_migration(False)
        self.assertEqual(self.state_column_count(), "0")
        self.assertFalse(self.kc.configured)

    def test_migration_preserves_data_and_can_run_twice(self):
        self.run_migration()
        first = self.q("SELECT account_ref FROM admin ORDER BY id;")
        self.run_migration()
        self.assertEqual(first, self.q("SELECT account_ref FROM admin ORDER BY id;"))
        self.assertEqual(self.q("SELECT state FROM admin ORDER BY id;"), "ACTIVE\nUNLINKED")
        self.assertEqual(self.q("SELECT name FROM admin WHERE id=1;"), "기존 이름")
        self.assertEqual(self.q("SELECT COUNT(*) FROM admin_account_lock;"), "1")
        self.assertEqual(self.q("SELECT COUNT(*) FROM admin_account_outbox;"), "0")
        self.assertEqual(self.q("SELECT COUNT(DISTINCT account_ref) FROM admin;"), "2")
        self.assertTrue(self.kc.configured)
        self.q("INSERT INTO admin (username,name,role) VALUES ('old-app','추가','ADMIN');")
        self.assertEqual(self.q("SELECT state FROM admin WHERE username='old-app';"), "UNLINKED")
        self.assertEqual(self.q("SELECT LENGTH(account_ref) FROM admin WHERE username='old-app';"), "36")

    def test_partial_ddl_failure_is_recoverable_without_losing_superadmin(self):
        def fail_table(sql):
            if "CREATE TABLE IF NOT EXISTS admin_account_outbox" in sql:
                raise RuntimeError("Injected DDL failure")
            return self.q(sql)
        with self.assertRaises(RuntimeError): self.run_migration(query=fail_table)
        self.run_migration()
        self.assertEqual(self.q("SELECT state FROM admin WHERE id=1;"), "ACTIVE")

    def test_wrong_identity_stops_before_ddl(self):
        self.kc.user["username"] = "someone-else"
        with self.assertRaises(RuntimeError): self.run_migration()
        self.assertEqual(self.state_column_count(), "0")
        self.assertFalse(self.kc.configured)

    def test_disabled_superadmin_stops_before_ddl(self):
        self.kc.user["enabled"] = False
        with self.assertRaises(RuntimeError): self.run_migration()
        self.assertEqual(self.state_column_count(), "0")

    def test_missing_linked_superadmin_stops_before_ddl(self):
        self.q("UPDATE admin SET keycloak_id=NULL;")
        with self.assertRaises(RuntimeError): self.run_migration()
        self.assertEqual(self.state_column_count(), "0")

    def test_duplicate_subjects_stop_before_ddl(self):
        self.q("UPDATE admin SET keycloak_id='verified-id' WHERE id=2;")
        with self.assertRaises(RuntimeError): self.run_migration()
        self.assertEqual(self.state_column_count(), "0")

    def test_deleted_account_does_not_require_remote_user_to_still_exist(self):
        self.run_migration()
        self.q("UPDATE admin SET state='DELETED',keycloak_id='removed-id' WHERE id=1;")
        self.run_migration()
        self.assertEqual(self.q("SELECT state FROM admin WHERE id=1;"), "DELETED")

    def test_existing_blocked_account_is_not_reenabled_on_rerun(self):
        self.run_migration()
        self.q("UPDATE admin SET state='BLOCKED' WHERE id=1;")
        self.run_migration()
        self.assertEqual(self.q("SELECT state FROM admin WHERE id=1;"), "BLOCKED")


if __name__ == "__main__":
    unittest.main()
