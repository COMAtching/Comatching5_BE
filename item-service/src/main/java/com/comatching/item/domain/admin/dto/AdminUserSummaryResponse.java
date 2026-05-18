package com.comatching.item.domain.admin.dto;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.dto.member.AdminUserProfileDto;

public record AdminUserSummaryResponse(
	Long id,
	String email,
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
			dto.nickname(),
			dto.gender(),
			dto.profileImageUrl(),
			inventoryCounts.matchingTicketCount(),
			inventoryCounts.optionTicketCount()
		);
	}
}
