package com.comatching.user.domain.admin.user.dto;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.dto.member.AdminUserProfileDto;

public record AdminUserSummaryResponse(
	Long id,
	String email,
	String realName,
	String nickname,
	Gender gender,
	String profileImageUrl,
	long matchingTicketCount,
	long optionTicketCount
) {
	public static AdminUserSummaryResponse from(AdminUserProfileDto dto, AdminInventoryCounts inventoryCounts) {
		return new AdminUserSummaryResponse(
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
