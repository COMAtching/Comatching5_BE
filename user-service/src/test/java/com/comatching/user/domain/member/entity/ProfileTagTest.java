package com.comatching.user.domain.member.entity;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.comatching.common.domain.enums.ProfileTagItem;

@DisplayName("ProfileTag 엔티티 테스트")
class ProfileTagTest {

	@Nested
	@DisplayName("생성")
	class Create {

		@Test
		@DisplayName("ProfileTagItem으로 ProfileTag를 생성할 수 있다")
		void shouldCreateWithTagItem() {
			// given
			ProfileTagItem item = ProfileTagItem.EGG_FACE;

			// when
			ProfileTag profileTag = new ProfileTag(item);

			// then
			assertThat(profileTag.getTag()).isEqualTo(ProfileTagItem.EGG_FACE);
			assertThat(profileTag.getProfile()).isNull();
		}
	}

	@Nested
	@DisplayName("assignProfile 메서드")
	class AssignProfile {

		@Test
		@DisplayName("프로필을 할당하면 연관관계가 설정된다")
		void shouldAssignProfile() {
			// given
			ProfileTag profileTag = new ProfileTag(ProfileTagItem.BRIGHT);
			Profile profile = createTestProfile();

			// when
			profileTag.assignProfile(profile);

			// then
			assertThat(profileTag.getProfile()).isEqualTo(profile);
		}
	}

	private Profile createTestProfile() {
		return Profile.builder()
			.gender(com.comatching.common.domain.enums.Gender.MALE)
			.birthDate(java.time.LocalDate.of(2000, 1, 1))
			.mbti("ENFP")
			.university("한국대학교")
			.major("컴퓨터공학과")
			.contactFrequency(com.comatching.common.domain.enums.ContactFrequency.FREQUENT)
			.build();
	}
}
