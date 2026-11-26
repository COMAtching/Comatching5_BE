-- Apply in comatching_item after 20261124_01_bank_deposit_observation.sql.
CREATE TABLE IF NOT EXISTS bank_approval_control (
 id INT PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT FALSE, epoch BIGINT NOT NULL DEFAULT 0,
 switched_at DATETIME(6), account_key CHAR(64), checkpoint_at VARCHAR(19),
 last_scan_at DATETIME(6), last_success DATETIME(6), last_problem_at DATETIME(6), transfer_held BOOLEAN NOT NULL DEFAULT FALSE, issue VARCHAR(160), queue_depth INT NOT NULL DEFAULT 0
);
INSERT INTO bank_approval_control (id) VALUES (1) ON DUPLICATE KEY UPDATE id=id;
CREATE TABLE IF NOT EXISTS bank_approval_switch (
 epoch BIGINT PRIMARY KEY, enabled BOOLEAN NOT NULL, changed_at DATETIME(6) NOT NULL, admin_id BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS bank_deposit_scan (
 seq BIGINT AUTO_INCREMENT PRIMARY KEY, scan_id CHAR(36) NOT NULL UNIQUE, digest CHAR(64) NOT NULL,
 coverage_from VARCHAR(19) NOT NULL, coverage_through VARCHAR(19) NOT NULL, page_count INT NOT NULL, credit_count INT NOT NULL,
 epoch BIGINT NOT NULL, eligible BOOLEAN NOT NULL, checkpoint_at VARCHAR(19), issue VARCHAR(160),
 received_at DATETIME(6) NOT NULL, published_at DATETIME(6), processed_at DATETIME(6),
 INDEX ix_scan_publish(published_at,seq)
);
CREATE TABLE IF NOT EXISTS bank_deposit_ledger (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, fingerprint CHAR(64) NOT NULL, occurrence_no INT NOT NULL,
 payer VARCHAR(256) NOT NULL, amount_krw BIGINT NOT NULL, paid_second VARCHAR(19) NOT NULL,
 identity_incomplete BOOLEAN NOT NULL, scan_id CHAR(36) NOT NULL, reason VARCHAR(64) NOT NULL, order_id BIGINT UNIQUE, linked_at DATETIME(6),
 UNIQUE KEY uk_deposit_occurrence(fingerprint,occurrence_no),
 INDEX ix_deposit_window(paid_second), INDEX ix_deposit_scan(scan_id), INDEX ix_deposit_payer(payer,amount_krw)
);
CREATE TABLE IF NOT EXISTS bank_order_review (
 order_id BIGINT PRIMARY KEY, reason VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS bank_scan_capture (
 capture_id CHAR(36) PRIMARY KEY, scan_id CHAR(36) NOT NULL, INDEX ix_bank_capture_scan(scan_id)
);
-- Run this migration once, including on installations which already receive v1 observations.
ALTER TABLE bank_deposit_observation ADD COLUMN received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
CREATE INDEX ix_bank_raw_pending ON bank_deposit_observation(published_at,received_at);
