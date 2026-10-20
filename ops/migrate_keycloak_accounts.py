#!/usr/bin/env python3
"""Verify existing Keycloak IDs, then perform an additive account-sync migration.

Default is read-only inspection. --apply explicitly enables schema/profile updates.
Never auto-links by username and never changes passwords or Keycloak users.
Uses the mysql CLI with a temporary 0600 defaults file; secrets are never arguments.
Run with application stopped. New code still needs a restart after migration.
"""
import argparse
import contextlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def properties(path):
    result = {}
    for line in path.read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def quoted(value):
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'


@contextlib.contextmanager
def database(props, executable):
    url = urllib.parse.urlparse(props["spring.datasource.url"].removeprefix("jdbc:"))
    if not url.path[1:] or not url.hostname:
        raise RuntimeError("Invalid JDBC URL")
    with tempfile.NamedTemporaryFile(mode="w") as config:
        os.chmod(config.name, 0o600)
        config.write("[client]\npassword=" + quoted(props["spring.datasource.password"]) + "\n")
        config.flush()
        args = [executable, "--defaults-file=" + config.name, "--no-login-paths", "--protocol=TCP",
                "--connect-timeout=5", "--default-character-set=utf8mb4", "--host=" + url.hostname,
                "--port=" + str(url.port or 3306), "--user=" + props["spring.datasource.username"],
                "--database=" + url.path[1:], "--batch", "--skip-column-names", "--raw"]
        def query(sql):
            completed = subprocess.run(args, input=sql, text=True, capture_output=True)
            if completed.returncode:
                # SQL errors may include user data; don't dump SQL or connection config.
                raise RuntimeError("MySQL migration/query failed; inspect schema and database permissions")
            return completed.stdout.strip()
        yield query


class Keycloak:
    def __init__(self, props):
        self.base = props["keycloak.server-url"].rstrip("/")
        self.realm = urllib.parse.quote(props["keycloak.realm"], safe="")
        auth_realm = urllib.parse.quote(props.get("keycloak.admin.realm", props["keycloak.realm"]), safe="")
        data = urllib.parse.urlencode(dict(grant_type="password", client_id=props["keycloak.client-id"],
                                          username=props["keycloak.admin.username"], password=props["keycloak.admin.password"])).encode()
        request = urllib.request.Request(self.base + "/realms/" + auth_realm + "/protocol/openid-connect/token", data=data)
        with urllib.request.urlopen(request, timeout=10) as response:
            self.token = json.load(response)["access_token"]

    def call(self, path, data=None):
        request = urllib.request.Request(self.base + "/admin/realms/" + self.realm + path,
                data=None if data is None else json.dumps(data).encode(),
                method="GET" if data is None else "PUT",
                headers={"Authorization": "Bearer " + self.token, "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=10) as response:
            body = response.read()
            return json.loads(body) if body else None

    def profile(self):
        profile = self.call("/users/profile")
        matches = [a for a in profile["attributes"] if a["name"] == "invitevip_admin_ref"]
        permissions = {"view": ["admin"], "edit": ["admin"]}
        if matches:
            if matches[0].get("permissions") != permissions:
                raise RuntimeError("Existing account ownership attribute must be visible/editable only by admin")
            return profile, False
        profile["attributes"].append({"name": "invitevip_admin_ref", "displayName": "InviteVIP account reference",
                "permissions": permissions, "validations": {"length": {"min": 36, "max": 36}}})
        return profile, True


def migrate(query, keycloak, apply, backup_dir):
    if query("SELECT COUNT(*) FROM (SELECT keycloak_id FROM admin WHERE keycloak_id IS NOT NULL AND TRIM(keycloak_id) <> '' GROUP BY keycloak_id HAVING COUNT(*) > 1) duplicate_ids;") != "0":
        raise RuntimeError("Duplicate Keycloak IDs must be reviewed before migration")
    columns = set(query("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='admin';").splitlines())
    first_migration = "state" not in columns
    state_expression = "state" if "state" in columns else "'UNLINKED'"
    raw = query("SELECT JSON_OBJECT('id',id,'username',username,'role',role,'keycloakId',keycloak_id,'state',"
                + state_expression + ") FROM admin ORDER BY id;")
    rows = [json.loads(line) for line in raw.splitlines() if line]
    verified = []
    for row in rows:
        if row["state"] in ("BLOCKED", "DELETING", "DELETED"):
            continue
        subject = row["keycloakId"]
        if not subject or not subject.strip():
            continue
        user = keycloak.call("/users/" + urllib.parse.quote(subject, safe=""))
        if user["id"] != subject or user["username"] != row["username"] or not user.get("enabled"):
            raise RuntimeError("Stored account ID is missing, disabled, or has a different username; review before migration")
        verified.append(row)
    if first_migration and not any(r["role"] == "SUPER_ADMIN" for r in verified):
        raise RuntimeError("No verified linked SUPER_ADMIN; explicitly link an administrator before migration")
    profile, needs_profile = keycloak.profile()
    print(json.dumps({"admins": len(rows), "verifiedLinked": len(verified), "unlinked": sum(not r["keycloakId"] for r in rows),
                      "firstMigration": first_migration, "profileAttributeNeeded": needs_profile, "apply": apply}))
    if not apply:
        return
    backup_dir.mkdir(parents=True, exist_ok=True)
    fd, backup = tempfile.mkstemp(prefix="admin-link-before-", suffix=".json", dir=backup_dir)
    with os.fdopen(fd, "w") as out:
        json.dump(rows, out, ensure_ascii=False, indent=2)
    if needs_profile:
        keycloak.call("/users/profile", profile)
    if "state" not in columns:
        query("ALTER TABLE admin ADD COLUMN state VARCHAR(30) NOT NULL DEFAULT 'UNLINKED';")
    if "account_ref" not in columns:
        query("ALTER TABLE admin ADD COLUMN account_ref VARCHAR(36) NULL;")
    query("UPDATE admin SET account_ref=UUID() WHERE account_ref IS NULL;")
    query("ALTER TABLE admin MODIFY account_ref VARCHAR(36) NOT NULL DEFAULT (UUID());")
    query("UPDATE admin SET keycloak_id=NULL WHERE TRIM(keycloak_id)='';")
    indexes = set(query("SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='admin';").splitlines())
    if "uk_admin_keycloak_id" not in indexes:
        query("ALTER TABLE admin ADD UNIQUE INDEX uk_admin_keycloak_id (keycloak_id);")
    if "uk_admin_account_ref" not in indexes:
        query("ALTER TABLE admin ADD UNIQUE INDEX uk_admin_account_ref (account_ref);")
    query((ROOT / "db/keycloak-account-tables.sql").read_text())
    if verified:
        ids = ",".join(str(int(row["id"])) for row in verified)
        query("UPDATE admin SET state='ACTIVE' WHERE state='UNLINKED' AND id IN (" + ids + ");")
    print("Migration complete; existing users/passwords unchanged. Backup: " + backup)
    print(query("SELECT state,COUNT(*) FROM admin GROUP BY state;"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--properties", type=Path, default=ROOT / "src/main/resources/application.properties")
    parser.add_argument("--mysql", default=shutil.which("mysql") or "/usr/local/mysql/bin/mysql")
    parser.add_argument("--backup-dir", type=Path, default=Path(tempfile.gettempdir()) / "invitevip-account-backups")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    props = properties(args.properties)
    with database(props, args.mysql) as query:
        migrate(query, Keycloak(props), args.apply, args.backup_dir)


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        raise SystemExit("Keycloak verification failed (HTTP " + str(error.code) + "); no credentials or response bodies logged")
    except Exception as error:
        raise SystemExit(str(error) if isinstance(error, RuntimeError) else "Migration failed (" + type(error).__name__ + ")")
