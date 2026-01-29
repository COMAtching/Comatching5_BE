package com.comatching.user.domain.member.service;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;

import com.comatching.common.domain.enums.DefaultHobby;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.domain.enums.ProfileTagCategory;
import com.comatching.common.domain.enums.ProfileTagGroup;
import com.comatching.common.domain.enums.ProfileTagItem;
import com.comatching.common.dto.member.HobbyCategoryResponse;
import com.comatching.user.domain.member.dto.TagCategoryResponse;
import com.comatching.user.domain.member.dto.TagCategoryResponse.TagGroupResponse;
import com.comatching.user.domain.member.dto.TagCategoryResponse.TagItemResponse;

@Service
public class EnumLookupService {

	public List<HobbyCategoryResponse> getHobbyCategories() {
		return Arrays.stream(HobbyCategory.values())
			.map(category -> new HobbyCategoryResponse(
				category.name(),
				category.getDescription(),
				DefaultHobby.getByCategory(category).stream()
					.map(hobby -> new HobbyCategoryResponse.HobbyItem(
						hobby.name(),
						hobby.getDisplayName()
					))
					.toList()
			))
			.toList();
	}

	public List<TagCategoryResponse> getProfileTags() {
		return Arrays.stream(ProfileTagCategory.values())
			.map(category -> new TagCategoryResponse(
				category.name(),
				category.getLabel(),
				ProfileTagGroup.getByCategory(category).stream()
					.map(group -> new TagGroupResponse(
						group.name(),
						group.getLabel(),
						ProfileTagItem.getByGroup(group).stream()
							.map(item -> new TagItemResponse(item.name(), item.getLabel()))
							.toList()
					))
					.toList()
			))
			.toList();
	}
}
