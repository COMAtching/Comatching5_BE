package com.comatching.common.dto.item;

import java.time.LocalDateTime;

import com.comatching.common.domain.enums.ItemRoute;
import com.comatching.common.domain.enums.ItemType;

public record AddItemRequest(

	ItemType itemType,
	int quantity,
	ItemRoute route,
	int expiredAt
) {
}
