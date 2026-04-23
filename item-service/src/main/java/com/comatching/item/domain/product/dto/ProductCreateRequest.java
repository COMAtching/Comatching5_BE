package com.comatching.item.domain.product.dto;

import java.util.List;

import com.comatching.common.domain.enums.ItemType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record ProductCreateRequest(
	@NotBlank(message = "상품명은 필수입니다.")
	String name,

	@Min(value = 1, message = "가격은 1원 이상이어야 합니다.")
	int price,

	boolean isActive,

	@NotEmpty(message = "구성품은 최소 1개 이상이어야 합니다.")
	List<@Valid ProductRewardCreateRequest> rewards
) {
	public record ProductRewardCreateRequest(
		@NotNull(message = "아이템 타입은 필수입니다.")
		ItemType itemType,

		@Min(value = 1, message = "구성품 수량은 1 이상이어야 합니다.")
		int quantity
	) {
	}
}
