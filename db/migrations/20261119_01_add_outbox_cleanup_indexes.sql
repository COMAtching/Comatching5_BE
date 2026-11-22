-- 기존 매칭·주문 outbox 테이블에 적용한다. 신규/이미 적용된 인덱스는 건너뛴다.

SET @ddl = IF(EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = 'comatching_matching' AND table_name = 'matching_outbox_events' AND index_name = 'idx_matching_outbox_cleanup'
), 'SELECT 1', 'CREATE INDEX idx_matching_outbox_cleanup ON comatching_matching.matching_outbox_events (status, published_at, id)');
PREPARE add_index FROM @ddl;
EXECUTE add_index;
DEALLOCATE PREPARE add_index;

SET @ddl = IF(EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = 'comatching_item' AND table_name = 'order_outbox_events' AND index_name = 'idx_order_outbox_cleanup'
), 'SELECT 1', 'CREATE INDEX idx_order_outbox_cleanup ON comatching_item.order_outbox_events (status, published_at, id)');
PREPARE add_index FROM @ddl;
EXECUTE add_index;
DEALLOCATE PREPARE add_index;
