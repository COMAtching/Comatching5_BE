package com.comatching.common.dto.member;

import com.comatching.common.domain.enums.Gender;

public record AdminUserProfileDto(
	Long id,
	String email,
	String realName,
	String nickname,
	Gender gender,
	String profileImageUrl
) {
}
