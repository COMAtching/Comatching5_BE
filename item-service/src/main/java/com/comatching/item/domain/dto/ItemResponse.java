package com.comatching.item.domain.dto;

import java.time.LocalDateTime;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.entity.Item;

public record ItemResponse(
	Long itemId,
	ItemType itemType,
	int quantity,
	LocalDateTime expiredAt
) {
	public static ItemResponse from(Item item) {
		return new ItemResponse(item.getId(), item.getItemType(), item.getQuantity(), item.getExpiredAt());
	}
}
