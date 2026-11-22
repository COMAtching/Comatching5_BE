-- run-local.sh가 반복 실행한다. 운영에서는 user-service 배포 전에 적용한다.
CREATE TABLE IF NOT EXISTS comatching_user.member_outbox_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    topic VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    available_at DATETIME(6) NOT NULL,
    processing_token CHAR(36) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    INDEX idx_member_outbox_ready (status, available_at, id),
    INDEX idx_member_outbox_stream (member_id, topic, status, id),
    INDEX idx_member_outbox_cleanup (status, published_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
