package com.comatching.item.domain.order.dto;

import java.time.LocalDateTime;

import com.comatching.item.domain.order.enums.OrderStatus;

public record OrderStatusChangedOutboxPayload(
	Long orderId,
	OrderStatus fromStatus,
	OrderStatus toStatus,
	LocalDateTime decidedAt,
	Long decidedByAdminId,
	String reason
) {
}
