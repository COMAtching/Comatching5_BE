package com.comatching.item.domain.admin.dto;

import java.util.List;

import com.comatching.common.domain.enums.Gender;
import com.comatching.item.domain.item.dto.ItemResponse;

public record AdminUserDetailResponse(
	Long id,
	String email,
	String nickname,
	Gender gender,
	String profileImageUrl,
	List<ItemResponse> items
) {
}
