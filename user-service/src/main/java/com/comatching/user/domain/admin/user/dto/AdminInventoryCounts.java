package com.comatching.user.domain.admin.user.dto;

public record AdminInventoryCounts(
	long matchingTicketCount,
	long optionTicketCount
) {
	public static AdminInventoryCounts empty() {
		return new AdminInventoryCounts(0, 0);
	}
}
