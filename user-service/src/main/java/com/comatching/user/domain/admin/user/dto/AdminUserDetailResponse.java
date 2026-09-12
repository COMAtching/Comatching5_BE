package com.comatching.user.domain.admin.user.dto;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.dto.member.AdminUserProfileDto;

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
	public static AdminUserDetailResponse from(AdminUserProfileDto dto, AdminInventoryCounts inventoryCounts) {
		return new AdminUserDetailResponse(
			dto.id(),
			dto.email(),
			dto.realName(),
			dto.nickname(),
			dto.gender(),
			dto.profileImageUrl(),
			inventoryCounts.matchingTicketCount(),
			inventoryCounts.optionTicketCount()
		);
	}
}
