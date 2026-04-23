package com.comatching.item.domain.order.dto;

import java.time.LocalDateTime;

import com.comatching.item.domain.order.enums.OrderStatus;

public record OrderCreatedOutboxPayload(
	Long orderId,
	Long memberId,
	String requestedItemName,
	String requesterRealName,
	String requesterUsername,
	int optionTicketQty,
	int matchingTicketQty,
	int requestedPrice,
	int expectedPrice,
	OrderStatus status,
	LocalDateTime requestedAt,
	LocalDateTime expiresAt
) {
}
