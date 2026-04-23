package com.comatching.item.domain.order.service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.comatching.item.domain.order.dto.AdminOrderRealtimeMessage;
import com.comatching.item.domain.order.entity.OrderOutboxEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OrderRealtimePublisher {

	public static final String ADMIN_ORDER_TOPIC = "/topic/admin/orders";

	private final SimpMessagingTemplate messagingTemplate;
	private final ObjectMapper objectMapper;

	public void publish(OrderOutboxEvent event) {
		JsonNode payload = readPayload(event.getPayload());
		AdminOrderRealtimeMessage message = new AdminOrderRealtimeMessage(
			event.getId(),
			event.getEventType(),
			event.getOccurredAt(),
			payload
		);

		messagingTemplate.convertAndSend(ADMIN_ORDER_TOPIC, message);
	}

	private JsonNode readPayload(String rawPayload) {
		try {
			return objectMapper.readTree(rawPayload);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to parse outbox payload json", e);
		}
	}
}
