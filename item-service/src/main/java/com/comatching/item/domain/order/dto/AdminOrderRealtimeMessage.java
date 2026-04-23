package com.comatching.item.domain.order.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.JsonNode;

public record AdminOrderRealtimeMessage(
	Long eventId,
	String eventType,
	LocalDateTime occurredAt,
	JsonNode payload
) {
}
