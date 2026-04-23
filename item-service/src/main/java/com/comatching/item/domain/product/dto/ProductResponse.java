package com.comatching.item.domain.product.dto;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.product.entity.Product;
import com.comatching.item.domain.product.entity.ProductReward;

import java.util.List;

public record ProductResponse(
	Long id,
	String name,
	int price,
	List<ProductRewardDto> rewards
) {
	public static ProductResponse from(Product product) {
		return new ProductResponse(
			product.getId(),
			product.getName(),
			product.getPrice(),
			product.getRewards().stream()
				.map(ProductRewardDto::from)
				.toList()
		);
	}
}

record ProductRewardDto(
	ItemType itemType,
	String itemName,
	int quantity
) {
	public static ProductRewardDto from(ProductReward reward) {
		return new ProductRewardDto(
			reward.getItemType(),
			reward.getItemType().getName(),
			reward.getQuantity()
		);
	}
}