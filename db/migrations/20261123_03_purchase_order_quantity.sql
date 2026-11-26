-- NULL은 전환 전 주문이다. 당시의 상품별 한도 계산 단위를 유지한다.
SET @ddl = IF(EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'comatching_item' AND table_name = 'purchase_order' AND column_name = 'product_quantity'
), 'SELECT 1', 'ALTER TABLE comatching_item.purchase_order ADD COLUMN product_quantity INT NULL');
PREPARE add_product_quantity FROM @ddl;
EXECUTE add_product_quantity;
DEALLOCATE PREPARE add_product_quantity;
