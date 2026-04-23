package com.comatching.item.domain.admin.dto;

import com.comatching.common.domain.enums.ItemType;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AdminInventoryUpdateRequest(
	@NotNull(message = "아이템 타입은 필수입니다.")
	ItemType itemType,

	@Positive(message = "수량은 1 이상이어야 합니다.")
	int quantity,

	@NotNull(message = "수정 액션은 필수입니다.")
	AdminInventoryAction action
) {
}
