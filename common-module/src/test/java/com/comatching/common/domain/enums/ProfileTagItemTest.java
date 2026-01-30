package com.comatching.common.domain.enums;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ProfileTagItem Enum 테스트")
class ProfileTagItemTest {

	@Nested
	@DisplayName("getGroup 메서드")
	class GetGroup {

		@Test
		@DisplayName("외모 카테고리의 태그는 올바른 그룹에 속한다")
		void shouldBelongToCorrectGroup() {
			// given
			ProfileTagItem eggFace = ProfileTagItem.EGG_FACE;

			// when
			ProfileTagGroup group = eggFace.getGroup();

			// then
			assertThat(group).isEqualTo(ProfileTagGroup.FACE_SHAPE);
			assertThat(group.getCategory()).isEqualTo(ProfileTagCategory.APPEARANCE);
		}

		@Test
		@DisplayName("모든 태그는 null이 아닌 그룹을 가진다")
		void allTagsShouldHaveNonNullGroup() {
			for (ProfileTagItem item : ProfileTagItem.values()) {
				assertThat(item.getGroup())
					.as("태그 '%s'의 그룹이 null", item.name())
					.isNotNull();
			}
		}

		@Test
		@DisplayName("모든 그룹은 null이 아닌 카테고리를 가진다")
		void allGroupsShouldHaveNonNullCategory() {
			for (ProfileTagGroup group : ProfileTagGroup.values()) {
				assertThat(group.getCategory())
					.as("그룹 '%s'의 카테고리가 null", group.name())
					.isNotNull();
			}
		}
	}

	@Nested
	@DisplayName("getLabel 메서드")
	class GetLabel {

		@Test
		@DisplayName("모든 태그는 비어있지 않은 라벨을 가진다")
		void allTagsShouldHaveNonEmptyLabel() {
			for (ProfileTagItem item : ProfileTagItem.values()) {
				assertThat(item.getLabel())
					.as("태그 '%s'의 라벨이 비어있음", item.name())
					.isNotBlank();
			}
		}

		@Test
		@DisplayName("모든 그룹은 비어있지 않은 라벨을 가진다")
		void allGroupsShouldHaveNonEmptyLabel() {
			for (ProfileTagGroup group : ProfileTagGroup.values()) {
				assertThat(group.getLabel())
					.as("그룹 '%s'의 라벨이 비어있음", group.name())
					.isNotBlank();
			}
		}

		@Test
		@DisplayName("모든 카테고리는 비어있지 않은 라벨을 가진다")
		void allCategoriesShouldHaveNonEmptyLabel() {
			for (ProfileTagCategory category : ProfileTagCategory.values()) {
				assertThat(category.getLabel())
					.as("카테고리 '%s'의 라벨이 비어있음", category.name())
					.isNotBlank();
			}
		}
	}

	@Nested
	@DisplayName("getByGroup 캐싱 메서드")
	class GetByGroup {

		@Test
		@DisplayName("각 그룹별 태그 목록을 올바르게 반환한다")
		void shouldReturnTagsForGroup() {
			// when
			List<ProfileTagItem> faceShapeTags = ProfileTagItem.getByGroup(ProfileTagGroup.FACE_SHAPE);

			// then
			assertThat(faceShapeTags).isNotEmpty();
			assertThat(faceShapeTags).allSatisfy(tag ->
				assertThat(tag.getGroup()).isEqualTo(ProfileTagGroup.FACE_SHAPE)
			);
		}

		@Test
		@DisplayName("모든 그룹에 대해 최소 1개 이상의 태그가 존재한다")
		void eachGroupShouldHaveAtLeastOneTag() {
			for (ProfileTagGroup group : ProfileTagGroup.values()) {
				List<ProfileTagItem> tags = ProfileTagItem.getByGroup(group);
				assertThat(tags)
					.as("그룹 '%s'에 속한 태그", group.name())
					.isNotEmpty();
			}
		}

		@Test
		@DisplayName("모든 그룹의 태그 합이 전체 ProfileTagItem 수와 같다")
		void totalTagsShouldMatchEnumSize() {
			long total = 0;
			for (ProfileTagGroup group : ProfileTagGroup.values()) {
				total += ProfileTagItem.getByGroup(group).size();
			}
			assertThat(total).isEqualTo(ProfileTagItem.values().length);
		}
	}

	@Nested
	@DisplayName("getByCategory 캐싱 메서드 (ProfileTagGroup)")
	class GetByCategory {

		@Test
		@DisplayName("각 카테고리별 그룹 목록을 올바르게 반환한다")
		void shouldReturnGroupsForCategory() {
			// when
			List<ProfileTagGroup> appearanceGroups = ProfileTagGroup.getByCategory(ProfileTagCategory.APPEARANCE);

			// then
			assertThat(appearanceGroups).isNotEmpty();
			assertThat(appearanceGroups).allSatisfy(group ->
				assertThat(group.getCategory()).isEqualTo(ProfileTagCategory.APPEARANCE)
			);
		}

		@Test
		@DisplayName("모든 카테고리에 대해 최소 1개 이상의 그룹이 존재한다")
		void eachCategoryShouldHaveAtLeastOneGroup() {
			for (ProfileTagCategory category : ProfileTagCategory.values()) {
				List<ProfileTagGroup> groups = ProfileTagGroup.getByCategory(category);
				assertThat(groups)
					.as("카테고리 '%s'에 속한 그룹", category.name())
					.isNotEmpty();
			}
		}

		@Test
		@DisplayName("모든 카테고리의 그룹 합이 전체 ProfileTagGroup 수와 같다")
		void totalGroupsShouldMatchEnumSize() {
			long total = 0;
			for (ProfileTagCategory category : ProfileTagCategory.values()) {
				total += ProfileTagGroup.getByCategory(category).size();
			}
			assertThat(total).isEqualTo(ProfileTagGroup.values().length);
		}
	}

	@Nested
	@DisplayName("카테고리 구조 검증")
	class CategoryStructure {

		@Test
		@DisplayName("4개의 카테고리가 존재한다")
		void shouldHaveFourCategories() {
			assertThat(ProfileTagCategory.values()).hasSize(4);
		}

		@Test
		@DisplayName("각 카테고리에 최소 1개 이상의 그룹이 속한다")
		void eachCategoryShouldHaveAtLeastOneGroup() {
			for (ProfileTagCategory category : ProfileTagCategory.values()) {
				long groupCount = java.util.Arrays.stream(ProfileTagGroup.values())
					.filter(g -> g.getCategory() == category)
					.count();
				assertThat(groupCount)
					.as("카테고리 '%s'에 속한 그룹 수", category.name())
					.isGreaterThanOrEqualTo(1);
			}
		}

		@Test
		@DisplayName("각 그룹에 최소 1개 이상의 태그가 속한다")
		void eachGroupShouldHaveAtLeastOneTag() {
			for (ProfileTagGroup group : ProfileTagGroup.values()) {
				long tagCount = java.util.Arrays.stream(ProfileTagItem.values())
					.filter(t -> t.getGroup() == group)
					.count();
				assertThat(tagCount)
					.as("그룹 '%s'에 속한 태그 수", group.name())
					.isGreaterThanOrEqualTo(1);
			}
		}
	}
}
