-- Run through ops/migrate_keycloak_accounts.py: it verifies existing identities and
-- adds admin.state/account_ref plus unique constraints before these tables.
CREATE TABLE IF NOT EXISTS admin_account_lock (
    id BIGINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT IGNORE INTO admin_account_lock (id) VALUES (1);

CREATE TABLE IF NOT EXISTS admin_account_outbox (
    admin_id BIGINT NOT NULL PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    last_error VARCHAR(500) NULL,
    INDEX idx_account_due (status, next_attempt_at),
    CONSTRAINT fk_account_job_admin FOREIGN KEY (admin_id) REFERENCES admin(id)
) ENGINE=InnoDB;
