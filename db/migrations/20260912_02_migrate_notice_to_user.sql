-- Move notice ownership from item-service to user-service.
-- Run with an account that can read comatching_item and write comatching_user.
-- The source table is intentionally retained for rollback safety.

CREATE TABLE IF NOT EXISTS comatching_user.notice (
    id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    start_time DATETIME(6) NOT NULL,
    end_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO comatching_user.notice (
    id,
    title,
    content,
    start_time,
    end_time
)
SELECT
    id,
    title,
    content,
    start_time,
    end_time
FROM comatching_item.notice;
