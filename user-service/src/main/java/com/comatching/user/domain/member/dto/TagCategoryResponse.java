package com.comatching.user.domain.member.dto;

import java.util.List;

public record TagCategoryResponse(
	String category,
	String categoryLabel,
	List<TagGroupResponse> groups
) {

	public record TagGroupResponse(
		String group,
		String groupLabel,
		List<TagItemResponse> tags
	) {}

	public record TagItemResponse(
		String tag,
		String label
	) {}
}
