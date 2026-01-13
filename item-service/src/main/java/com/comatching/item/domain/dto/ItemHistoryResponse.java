package com.comatching.item.domain.dto;

import java.time.LocalDateTime;
import com.comatching.item.domain.entity.ItemHistory;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.enums.ItemHistoryType;

public record ItemHistoryResponse(
	Long historyId,
	ItemType itemType,
	ItemHistoryType historyType,
	int quantity,
	String description,
	LocalDateTime createdAt
) {
	public static ItemHistoryResponse from(ItemHistory history) {
		return new ItemHistoryResponse(
			history.getId(),
			history.getItemType(),
			history.getHistoryType(),
			history.getQuantity(),
			history.getDescription(),
			history.getCreatedAt()
		);
	}
}
