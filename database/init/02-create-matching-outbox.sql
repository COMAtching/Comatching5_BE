-- 01-create-databases.sql 이후 실행한다.
-- 기존 테이블/데이터는 유지한다. 이미 있는 테이블의 구조를 변경하지는 않는다.
-- 운영은 ddl-auto=validate이므로 이 엔티티를 포함한 애플리케이션 배포 전에
-- 대상 DB를 확인하고 별도로 적용한다. 이 파일이 운영에서 자동 실행되지는 않는다.
CREATE TABLE IF NOT EXISTS comatching_matching.matching_outbox_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    matching_id BIGINT NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INT NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    processing_token VARCHAR(36) NULL,
    processing_until DATETIME(6) NULL,
    last_error VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_matching_outbox_matching_id UNIQUE (matching_id),
    INDEX idx_matching_outbox_pending (status, next_attempt_at, id),
    INDEX idx_matching_outbox_processing (status, processing_until, id),
    INDEX idx_matching_outbox_cleanup (status, published_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
