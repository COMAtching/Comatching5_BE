-- 기존 주문은 환불 이력이 없는 상태로 시작한다. item-service 배포 전 1회 적용.
ALTER TABLE comatching_item.purchase_order
    ADD COLUMN refund_status VARCHAR(24) NOT NULL DEFAULT 'NONE',
    ADD COLUMN refund_started_at DATETIME(6) NULL,
    ADD COLUMN refund_started_by BIGINT NULL,
    ADD COLUMN refund_completed_at DATETIME(6) NULL,
    ADD COLUMN refund_completed_by BIGINT NULL,
    ADD INDEX idx_order_refund (refund_status, id);
