package com.comatching.user.domain.member.service;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.comatching.common.domain.enums.DefaultHobby;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.domain.enums.ProfileTagCategory;
import com.comatching.common.domain.enums.ProfileTagGroup;
import com.comatching.common.domain.enums.ProfileTagItem;
import com.comatching.common.dto.member.HobbyCategoryResponse;
import com.comatching.user.domain.member.dto.TagCategoryResponse;

@DisplayName("EnumLookupService 단위 테스트")
class EnumLookupServiceTest {

	private EnumLookupService enumLookupService;

	@BeforeEach
	void setUp() {
		enumLookupService = new EnumLookupService();
	}

	@Nested
	@DisplayName("getHobbyCategories 메서드")
	class GetHobbyCategories {

		@Test
		@DisplayName("모든 HobbyCategory가 응답에 포함된다")
		void shouldContainAllHobbyCategories() {
			// when
			List<HobbyCategoryResponse> result = enumLookupService.getHobbyCategories();

			// then
			assertThat(result).hasSize(HobbyCategory.values().length);
		}

		@Test
		@DisplayName("각 카테고리의 이름과 설명이 올바르게 매핑된다")
		void shouldMapCategoryNameAndDescription() {
			// when
			List<HobbyCategoryResponse> result = enumLookupService.getHobbyCategories();

			// then
			HobbyCategoryResponse sportsCategory = result.stream()
				.filter(r -> r.category().equals("SPORTS"))
				.findFirst()
				.orElseThrow();

			assertThat(sportsCategory.categoryName()).isEqualTo("스포츠");
			assertThat(sportsCategory.hobbies()).isNotEmpty();
		}

		@Test
		@DisplayName("각 카테고리에 속한 취미 항목이 올바르게 매핑된다")
		void shouldMapHobbyItemsCorrectly() {
			// when
			List<HobbyCategoryResponse> result = enumLookupService.getHobbyCategories();

			// then
			HobbyCategoryResponse sportsCategory = result.stream()
				.filter(r -> r.category().equals("SPORTS"))
				.findFirst()
				.orElseThrow();

			List<String> hobbyCodes = sportsCategory.hobbies().stream()
				.map(HobbyCategoryResponse.HobbyItem::code)
				.toList();

			assertThat(hobbyCodes).contains("SOCCER", "BASKETBALL", "TENNIS");
		}

		@Test
		@DisplayName("모든 DefaultHobby가 어딘가에 포함된다")
		void shouldContainAllDefaultHobbies() {
			// when
			List<HobbyCategoryResponse> result = enumLookupService.getHobbyCategories();

			// then
			long totalHobbies = result.stream()
				.mapToLong(r -> r.hobbies().size())
				.sum();

			assertThat(totalHobbies).isEqualTo(DefaultHobby.values().length);
		}

		@Test
		@DisplayName("취미 항목의 displayName이 비어있지 않다")
		void shouldHaveNonEmptyDisplayNames() {
			// when
			List<HobbyCategoryResponse> result = enumLookupService.getHobbyCategories();

			// then
			result.stream()
				.flatMap(r -> r.hobbies().stream())
				.forEach(hobby -> {
					assertThat(hobby.displayName())
						.as("취미 '%s'의 displayName", hobby.code())
						.isNotBlank();
				});
		}
	}

	@Nested
	@DisplayName("getProfileTags 메서드")
	class GetProfileTags {

		@Test
		@DisplayName("모든 ProfileTagCategory가 응답에 포함된다")
		void shouldContainAllTagCategories() {
			// when
			List<TagCategoryResponse> result = enumLookupService.getProfileTags();

			// then
			assertThat(result).hasSize(ProfileTagCategory.values().length);
		}

		@Test
		@DisplayName("카테고리-그룹-태그 3단계 구조가 올바르게 매핑된다")
		void shouldMapThreeLevelHierarchy() {
			// when
			List<TagCategoryResponse> result = enumLookupService.getProfileTags();

			// then
			TagCategoryResponse appearance = result.stream()
				.filter(r -> r.category().equals("APPEARANCE"))
				.findFirst()
				.orElseThrow();

			assertThat(appearance.categoryLabel()).isEqualTo("외모");
			assertThat(appearance.groups()).isNotEmpty();

			TagCategoryResponse.TagGroupResponse faceShape = appearance.groups().stream()
				.filter(g -> g.group().equals("FACE_SHAPE"))
				.findFirst()
				.orElseThrow();

			assertThat(faceShape.groupLabel()).isEqualTo("얼굴형");
			assertThat(faceShape.tags()).isNotEmpty();
			assertThat(faceShape.tags().stream().map(TagCategoryResponse.TagItemResponse::tag))
				.contains("EGG_FACE", "ROUND_FACE");
		}

		@Test
		@DisplayName("모든 ProfileTagGroup이 어딘가에 포함된다")
		void shouldContainAllGroups() {
			// when
			List<TagCategoryResponse> result = enumLookupService.getProfileTags();

			// then
			long totalGroups = result.stream()
				.mapToLong(r -> r.groups().size())
				.sum();

			assertThat(totalGroups).isEqualTo(ProfileTagGroup.values().length);
		}

		@Test
		@DisplayName("모든 ProfileTagItem이 어딘가에 포함된다")
		void shouldContainAllTagItems() {
			// when
			List<TagCategoryResponse> result = enumLookupService.getProfileTags();

			// then
			long totalTags = result.stream()
				.flatMap(r -> r.groups().stream())
				.mapToLong(g -> g.tags().size())
				.sum();

			assertThat(totalTags).isEqualTo(ProfileTagItem.values().length);
		}

		@Test
		@DisplayName("태그 아이템의 label이 비어있지 않다")
		void shouldHaveNonEmptyLabels() {
			// when
			List<TagCategoryResponse> result = enumLookupService.getProfileTags();

			// then
			result.stream()
				.flatMap(r -> r.groups().stream())
				.flatMap(g -> g.tags().stream())
				.forEach(tag -> {
					assertThat(tag.label())
						.as("태그 '%s'의 label", tag.tag())
						.isNotBlank();
				});
		}
	}
}
