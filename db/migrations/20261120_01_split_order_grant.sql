-- 기존 데이터가 있는 DB용 1회 마이그레이션. 주문 쓰기와 워커를 중지한 상태에서 실행한다.
-- 기존 행의 초기값은 REVIEW_REQUIRED. 상태가 모호한 과거 주문은 자동 지급하지 않는다.
ALTER TABLE comatching_item.purchase_order
    ADD COLUMN grant_status VARCHAR(24) NOT NULL DEFAULT 'REVIEW_REQUIRED',
    ADD COLUMN grant_attempt INT NOT NULL DEFAULT 0,
    ADD INDEX idx_order_grant_work (status, grant_status, id);

UPDATE comatching_item.purchase_order
SET grant_status = 'NOT_REQUESTED'
WHERE status IN ('REJECTED','CANCELED','EXPIRED') AND grant_status = 'REVIEW_REQUIRED';

-- 기존 승인 주문 중 모든 구성품에 회원·종류·수량이 일치하는 지급 원장이 있을 때만 완료로 분류한다.
UPDATE comatching_item.purchase_order o
SET grant_status = 'GRANTED'
WHERE o.status = 'APPROVED' AND o.grant_status = 'REVIEW_REQUIRED'
  AND EXISTS (SELECT 1 FROM comatching_item.purchase_order_item i WHERE i.order_id = o.id)
  AND NOT EXISTS (
    SELECT 1 FROM comatching_item.purchase_order_item i
    WHERE i.order_id = o.id AND NOT EXISTS (
      SELECT 1 FROM comatching_item.order_grant_ledger l
      WHERE l.order_id = o.id AND l.order_item_id = i.id AND l.member_id = o.member_id
        AND l.item_type = i.item_type AND l.quantity = i.quantity
    )
  );

ALTER TABLE comatching_item.purchase_order ALTER COLUMN grant_status SET DEFAULT 'NOT_REQUESTED';

-- 기존 PENDING 주문에는 '과거 재고만 커밋된 경우'가 섞일 수 있다. 수동 대조 후 건별로 판단한다.
SELECT id, member_id, status, grant_status FROM comatching_item.purchase_order
WHERE grant_status = 'REVIEW_REQUIRED' ORDER BY id;
