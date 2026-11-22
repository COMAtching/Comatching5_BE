CREATE DATABASE IF NOT EXISTS comatching_notification
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS comatching_notification.notification_delivery (
    event_id VARCHAR(100) NOT NULL,
    handler_id VARCHAR(40) NOT NULL,
    state VARCHAR(20) NOT NULL,
    attempt_id CHAR(36) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id, handler_id),
    INDEX idx_delivery_state_time (state, updated_at)
) ENGINE=InnoDB;
