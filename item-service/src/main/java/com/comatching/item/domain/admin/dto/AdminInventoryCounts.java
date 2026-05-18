package com.comatching.item.domain.admin.dto;

public record AdminInventoryCounts(
	long matchingTicketCount,
	long optionTicketCount
) {
	public static AdminInventoryCounts empty() {
		return new AdminInventoryCounts(0, 0);
	}
}
