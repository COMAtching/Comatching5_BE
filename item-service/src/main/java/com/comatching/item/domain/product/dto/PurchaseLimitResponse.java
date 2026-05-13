package com.comatching.item.domain.product.dto;

import java.util.List;

import com.comatching.common.domain.enums.ItemType;

public record PurchaseLimitResponse(
	List<ItemPurchaseLimitResponse> limits
) {
	public record ItemPurchaseLimitResponse(
		ItemType itemType,
		String itemName,
		long ownedQuantity,
		long pendingQuantity,
		int maxQuantity,
		long remainingQuantity,
		boolean purchasable
	) {
		public static ItemPurchaseLimitResponse of(
			ItemType itemType,
			long ownedQuantity,
			long pendingQuantity,
			int maxQuantity
		) {
			long remainingQuantity = Math.max(0, maxQuantity - ownedQuantity - pendingQuantity);
			return new ItemPurchaseLimitResponse(
				itemType,
				itemType.getName(),
				ownedQuantity,
				pendingQuantity,
				maxQuantity,
				remainingQuantity,
				remainingQuantity > 0
			);
		}
	}
}
