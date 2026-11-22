package com.comatching.item.domain.order.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.domain.enums.ItemRoute;
import com.comatching.common.dto.item.AddItemRequest;
import com.comatching.item.domain.item.service.ItemService;
import com.comatching.item.domain.order.entity.Order;
import com.comatching.item.domain.order.entity.OrderGrantLedger;
import com.comatching.item.domain.order.entity.OrderItem;
import com.comatching.item.domain.order.enums.GrantStatus;
import com.comatching.item.domain.order.enums.OrderStatus;
import com.comatching.item.domain.order.enums.RefundStatus;
import com.comatching.item.domain.order.outbox.OrderGrantOutboxRepository;
import com.comatching.item.domain.order.repository.OrderGrantLedgerRepository;
import com.comatching.item.domain.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderGrantTransaction {
	private static final int PURCHASED_ITEM_EXPIRE_DAYS = 36500;

	private final OrderRepository orders;
	private final OrderGrantLedgerRepository ledger;
	private final ItemService items;
	private final OrderGrantOutboxRepository grantOutbox;

	@Transactional
	public void grant(Long orderId, int attempt) {
		Order order = orders.findByIdForUpdate(orderId).orElseThrow();
		if (order.getStatus() != OrderStatus.APPROVED || order.getGrantStatus() != GrantStatus.PENDING
				|| order.getRefundStatus() != RefundStatus.NONE || order.getGrantAttempt() != attempt) {
			return;
		}

		if (order.getOrderItems().isEmpty()) {
			throw new IllegalStateException("구성품 없는 주문: " + orderId);
		}
		LocalDateTime grantedAt = LocalDateTime.now();
		for (OrderItem line : order.getOrderItems()) {
			// (order_id, order_item_id) UNIQUE. 정상적으로 지급된 주문은 위의 상태 검사에서 끝난다.
			// PENDING인데 원장이 이미 있다면 임의로 나머지를 채우지 않고 트랜잭션을 실패시킨다.
			ledger.saveAndFlush(OrderGrantLedger.builder()
					.orderId(orderId).orderItemId(line.getId()).memberId(order.getMemberId())
					.itemType(line.getItemType()).quantity(line.getQuantity())
					.grantedAt(grantedAt).build());
			items.addUnderInventoryLock(order.getMemberId(),
					new AddItemRequest(line.getItemType(), line.getQuantity(), ItemRoute.CHARGE, PURCHASED_ITEM_EXPIRE_DAYS));
		}
		order.completeGrant();
		grantOutbox.enqueue(order, grantedAt);
	}
}
