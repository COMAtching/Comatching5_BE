package com.comatching.common.dto.member;

import java.util.List;

public record HobbyCategoryResponse(
	String category,
	String categoryName,
	List<HobbyItem> hobbies
) {
	public record HobbyItem(
		String code,
		String displayName
	) {
	}
}
