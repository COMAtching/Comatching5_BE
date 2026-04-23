package com.comatching.common.dto.member;

import com.comatching.common.domain.enums.Gender;

public record AdminUserProfileDto(
	Long id,
	String email,
	String nickname,
	Gender gender,
	String profileImageUrl
) {
}
