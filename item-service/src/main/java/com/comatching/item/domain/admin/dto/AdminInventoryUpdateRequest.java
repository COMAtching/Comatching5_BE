package com.comatching.item.domain.admin.dto;

import com.comatching.common.domain.enums.ItemType;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AdminInventoryUpdateRequest(
	@NotNull(message = "아이템 타입은 필수입니다.")
	ItemType itemType,

	@Positive(message = "수량은 1 이상이어야 합니다.")
	int quantity,

	@NotNull(message = "수정 액션은 필수입니다.")
	AdminInventoryAction action,

	@NotBlank(message = "수정 사유는 필수입니다.")
	@Size(max = 255, message = "수정 사유는 255자 이하여야 합니다.")
	String reason
) {
	public AdminInventoryUpdateRequest {
		if (reason != null) {
			reason = reason.trim();
		}
	}
}
