package com.comatching.user.domain.admin.dto;

import com.comatching.common.domain.enums.Gender;

public record AdminUserDetailResponse(
	Long id,
	String email,
	String realName,
	String nickname,
	Gender gender,
	String profileImageUrl,
	long matchingTicketCount,
	long optionTicketCount
) {
}
