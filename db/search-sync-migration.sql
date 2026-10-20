-- Apply once (safe to repeat) to customer_db BEFORE starting the updated application.
-- No existing customer data or legacy Elasticsearch index is changed by this migration.
CREATE TABLE IF NOT EXISTS customer_search_lock (
    id BIGINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;

INSERT IGNORE INTO customer_search_lock (id) VALUES (1), (2);

CREATE TABLE IF NOT EXISTS customer_search_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL,
    last_error VARCHAR(1000) NULL,
    INDEX ix_search_outbox_due (status, next_attempt_at, id),
    INDEX ix_search_outbox_customer (customer_id, id)
) ENGINE=InnoDB;

-- Intentionally no customer FK: deletion events must survive customer deletion.
-- Do not TRUNCATE/reset AUTO_INCREMENT; IDs are persistent ES version numbers.
