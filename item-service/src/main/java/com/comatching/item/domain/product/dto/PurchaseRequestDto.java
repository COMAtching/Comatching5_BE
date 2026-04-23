package com.comatching.item.domain.product.dto;

import com.comatching.item.domain.product.entity.PurchaseRequest;
import com.comatching.item.domain.product.enums.PurchaseStatus;

import java.time.LocalDateTime;

public record PurchaseRequestDto(
	Long requestId,
	Long memberId,
	String productName,
	int paymentPrice,
	PurchaseStatus status,
	LocalDateTime requestedAt
) {
	public static PurchaseRequestDto from(PurchaseRequest request) {
		return new PurchaseRequestDto(
			request.getId(),
			request.getMemberId(),
			request.getProductName(),
			request.getPaymentPrice(),
			request.getStatus(),
			request.getRequestedAt()
		);
	}
}