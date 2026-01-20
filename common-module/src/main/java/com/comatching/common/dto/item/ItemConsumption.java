package com.comatching.common.dto.item;

import com.comatching.common.domain.enums.ItemType;

public record ItemConsumption(
	ItemType itemType,
	int quantity
) {
}
