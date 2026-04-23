package com.comatching.item.domain.product.dto;

public record PurchasePendingStatusResponse(
	String status
) {
	private static final String PENDING = "PENDING";
	private static final String NONE = "NONE";

	public static PurchasePendingStatusResponse pending() {
		return new PurchasePendingStatusResponse(PENDING);
	}

	public static PurchasePendingStatusResponse none() {
		return new PurchasePendingStatusResponse(NONE);
	}
}
