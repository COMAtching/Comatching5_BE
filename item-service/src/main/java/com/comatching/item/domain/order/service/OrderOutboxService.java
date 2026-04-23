package com.comatching.item.domain.order.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.order.dto.OrderCreatedOutboxPayload;
import com.comatching.item.domain.order.dto.OrderStatusChangedOutboxPayload;
import com.comatching.item.domain.order.entity.Order;
import com.comatching.item.domain.order.entity.OrderOutboxEvent;
import com.comatching.item.domain.order.enums.OrderStatus;
import com.comatching.item.domain.order.repository.OrderOutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderOutboxService {

	public static final String AGGREGATE_TYPE_ORDER = "ORDER";
	public static final String EVENT_TYPE_ORDER_CREATED = "ORDER_CREATED";
	public static final String EVENT_TYPE_ORDER_STATUS_CHANGED = "ORDER_STATUS_CHANGED";

	private final OrderOutboxEventRepository orderOutboxEventRepository;
	private final ObjectMapper objectMapper;

	public void enqueueOrderCreated(Order order) {
		OrderCreatedOutboxPayload payload = new OrderCreatedOutboxPayload(
			order.getId(),
			order.getMemberId(),
			order.getRequestedItemName(),
			order.getRequesterRealName(),
			order.getRequesterUsername(),
			getQuantity(order, ItemType.OPTION_TICKET),
			getQuantity(order, ItemType.MATCHING_TICKET),
			order.getRequestedPrice(),
			order.getExpectedPrice(),
			order.getStatus(),
			order.getRequestedAt(),
			order.getExpiresAt()
		);

		enqueue(order.getId(), EVENT_TYPE_ORDER_CREATED, payload, order.getRequestedAt());
	}

	public void enqueueOrderStatusChanged(
		Long orderId,
		OrderStatus fromStatus,
		OrderStatus toStatus,
		LocalDateTime decidedAt,
		Long decidedByAdminId,
		String reason
	) {
		OrderStatusChangedOutboxPayload payload = new OrderStatusChangedOutboxPayload(
			orderId,
			fromStatus,
			toStatus,
			decidedAt,
			decidedByAdminId,
			reason
		);
		enqueue(orderId, EVENT_TYPE_ORDER_STATUS_CHANGED, payload, decidedAt);
	}

	private int getQuantity(Order order, ItemType itemType) {
		return order.getOrderItems().stream()
			.filter(orderItem -> orderItem.getItemType() == itemType)
			.mapToInt(orderItem -> orderItem.getQuantity())
			.sum();
	}

	private void enqueue(Long aggregateId, String eventType, Object payload, LocalDateTime occurredAt) {
		String payloadJson = toJson(payload);
		OrderOutboxEvent event = OrderOutboxEvent.builder()
			.aggregateType(AGGREGATE_TYPE_ORDER)
			.aggregateId(aggregateId)
			.eventType(eventType)
			.payload(payloadJson)
			.occurredAt(occurredAt)
			.build();
		orderOutboxEventRepository.save(event);
	}

	private String toJson(Object payload) {
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to serialize order outbox payload", e);
		}
	}
}
