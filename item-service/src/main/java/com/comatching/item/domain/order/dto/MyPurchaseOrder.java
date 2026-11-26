package com.comatching.item.domain.order.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

public record MyPurchaseOrder(
	long orderId, String productName, Integer quantity, int amount, OffsetDateTime requestedAt,
	OffsetDateTime expiresAt, String status,
	@JsonInclude(JsonInclude.Include.NON_NULL) String depositorName
) {}
