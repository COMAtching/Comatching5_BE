package com.comatching.common.domain.enums;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultHobby Enum 테스트")
class DefaultHobbyTest {

	@Nested
	@DisplayName("getByCategory 메서드")
	class GetByCategory {

		@Test
		@DisplayName("각 카테고리별 취미 목록을 올바르게 반환한다")
		void shouldReturnHobbiesForCategory() {
			// when
			List<DefaultHobby> sports = DefaultHobby.getByCategory(HobbyCategory.SPORTS);

			// then
			assertThat(sports).isNotEmpty();
			assertThat(sports).allSatisfy(hobby ->
				assertThat(hobby.getCategory()).isEqualTo(HobbyCategory.SPORTS)
			);
		}

		@Test
		@DisplayName("모든 카테고리에 대해 최소 1개 이상의 취미가 존재한다")
		void eachCategoryShouldHaveAtLeastOneHobby() {
			for (HobbyCategory category : HobbyCategory.values()) {
				List<DefaultHobby> hobbies = DefaultHobby.getByCategory(category);
				assertThat(hobbies)
					.as("카테고리 '%s'에 속한 취미", category.name())
					.isNotEmpty();
			}
		}

		@Test
		@DisplayName("모든 카테고리의 취미 합이 전체 DefaultHobby 수와 같다")
		void totalHobbiesShouldMatchEnumSize() {
			long total = 0;
			for (HobbyCategory category : HobbyCategory.values()) {
				total += DefaultHobby.getByCategory(category).size();
			}
			assertThat(total).isEqualTo(DefaultHobby.values().length);
		}

		@Test
		@DisplayName("SPORTS 카테고리에 축구, 농구가 포함된다")
		void sportsShouldContainExpectedHobbies() {
			List<DefaultHobby> sports = DefaultHobby.getByCategory(HobbyCategory.SPORTS);
			assertThat(sports).contains(DefaultHobby.SOCCER, DefaultHobby.BASKETBALL);
		}
	}

	@Nested
	@DisplayName("findByDisplayName 메서드")
	class FindByDisplayName {

		@Test
		@DisplayName("존재하는 displayName으로 취미를 찾을 수 있다")
		void shouldFindByExistingDisplayName() {
			// when
			Optional<DefaultHobby> result = DefaultHobby.findByDisplayName("⚽ 축구");

			// then
			assertThat(result).isPresent();
			assertThat(result.get()).isEqualTo(DefaultHobby.SOCCER);
		}

		@Test
		@DisplayName("존재하지 않는 displayName은 빈 Optional을 반환한다")
		void shouldReturnEmptyForNonExisting() {
			// when
			Optional<DefaultHobby> result = DefaultHobby.findByDisplayName("존재하지않는취미");

			// then
			assertThat(result).isEmpty();
		}

		@Test
		@DisplayName("모든 DefaultHobby의 displayName으로 조회할 수 있다")
		void allHobbiesShouldBeFoundByDisplayName() {
			for (DefaultHobby hobby : DefaultHobby.values()) {
				Optional<DefaultHobby> found = DefaultHobby.findByDisplayName(hobby.getDisplayName());
				assertThat(found)
					.as("displayName '%s'으로 조회", hobby.getDisplayName())
					.isPresent()
					.hasValue(hobby);
			}
		}
	}
}
