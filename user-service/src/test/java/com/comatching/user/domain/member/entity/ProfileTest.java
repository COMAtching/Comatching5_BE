package com.comatching.user.domain.member.entity;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.ProfileTagItem;
import com.comatching.common.exception.BusinessException;

@DisplayName("Profile 태그 관리 테스트")
class ProfileTest {

	private Profile profile;

	@BeforeEach
	void setUp() {
		profile = Profile.builder()
			.gender(Gender.MALE)
			.birthDate(LocalDate.of(2000, 1, 1))
			.mbti("ENFP")
			.university("한국대학교")
			.major("컴퓨터공학과")
			.contactFrequency(ContactFrequency.FREQUENT)
			.build();
	}

	@Nested
	@DisplayName("addTags 메서드")
	class AddTags {

		@Test
		@DisplayName("유효한 태그 목록을 추가하면 정상적으로 저장된다")
		void shouldAddTagsSuccessfully() {
			// given
			List<ProfileTag> tags = List.of(
				new ProfileTag(ProfileTagItem.EGG_FACE),
				new ProfileTag(ProfileTagItem.BRIGHT),
				new ProfileTag(ProfileTagItem.SLIM)
			);

			// when
			profile.addTags(tags);

			// then
			assertThat(profile.getTags()).hasSize(3);
		}

		@Test
		@DisplayName("카테고리별 3개를 초과하면 BusinessException이 발생한다")
		void shouldThrowWhenExceedingCategoryLimit() {
			// given - 외모(APPEARANCE) 카테고리 태그 4개
			List<ProfileTag> tags = List.of(
				new ProfileTag(ProfileTagItem.EGG_FACE),
				new ProfileTag(ProfileTagItem.ANGULAR_FACE),
				new ProfileTag(ProfileTagItem.ROUND_FACE),
				new ProfileTag(ProfileTagItem.SHARP_FACE)
			);

			// when & then
			assertThatThrownBy(() -> profile.addTags(tags))
				.isInstanceOf(BusinessException.class);
		}

		@Test
		@DisplayName("null 태그 목록을 전달하면 기존 태그가 모두 제거된다")
		void shouldClearTagsWhenNull() {
			// given
			profile.addTags(List.of(new ProfileTag(ProfileTagItem.BRIGHT)));
			assertThat(profile.getTags()).hasSize(1);

			// when
			profile.addTags(null);

			// then
			assertThat(profile.getTags()).isEmpty();
		}

		@Test
		@DisplayName("새 태그를 추가하면 기존 태그가 교체된다")
		void shouldReplaceExistingTags() {
			// given
			profile.addTags(List.of(new ProfileTag(ProfileTagItem.BRIGHT)));
			assertThat(profile.getTags()).hasSize(1);

			List<ProfileTag> newTags = List.of(
				new ProfileTag(ProfileTagItem.SLIM),
				new ProfileTag(ProfileTagItem.MUSCULAR)
			);

			// when
			profile.addTags(newTags);

			// then
			assertThat(profile.getTags()).hasSize(2);
			assertThat(profile.getTags())
				.extracting(ProfileTag::getTag)
				.containsExactlyInAnyOrder(ProfileTagItem.SLIM, ProfileTagItem.MUSCULAR);
		}

		@Test
		@DisplayName("서로 다른 카테고리의 태그는 각각 최대 3개까지 허용된다")
		void shouldAllowThreeTagsPerCategory() {
			// given - 외모 3개 + 성격 3개 = 총 6개
			List<ProfileTag> tags = List.of(
				new ProfileTag(ProfileTagItem.EGG_FACE),
				new ProfileTag(ProfileTagItem.DIMPLE),
				new ProfileTag(ProfileTagItem.FAIR_SKIN),
				new ProfileTag(ProfileTagItem.EXTROVERT),
				new ProfileTag(ProfileTagItem.CARING),
				new ProfileTag(ProfileTagItem.LOGICAL)
			);

			// when
			profile.addTags(tags);

			// then
			assertThat(profile.getTags()).hasSize(6);
		}

		@Test
		@DisplayName("태그에 프로필이 할당된다")
		void shouldAssignProfileToTags() {
			// given
			List<ProfileTag> tags = new ArrayList<>(List.of(new ProfileTag(ProfileTagItem.BRIGHT)));

			// when
			profile.addTags(tags);

			// then
			assertThat(profile.getTags().get(0).getProfile()).isEqualTo(profile);
		}
	}
}
