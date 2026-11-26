-- Apply to the item-service database (MySQL 8+) before enabling deposit ingestion.
CREATE TABLE IF NOT EXISTS bank_deposit_observation (
    event_id VARCHAR(96) NOT NULL PRIMARY KEY,
    payload TEXT NOT NULL,
    available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_token CHAR(36),
    published_at TIMESTAMP(6) NULL DEFAULT NULL,
    INDEX ix_bank_observation_pending (published_at, available_at, event_id),
    INDEX ix_bank_observation_lease (lease_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
