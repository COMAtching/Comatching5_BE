package com.comatching.common.dto.member;

import com.comatching.common.domain.enums.HobbyCategory;

public record HobbyDto(
	HobbyCategory category,
	String name
) {
}
