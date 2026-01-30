package com.comatching.user.domain.member.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.domain.enums.ProfileTagItem;
import com.comatching.common.dto.member.HobbyDto;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.dto.member.ProfileTagDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.user.domain.event.UserEventPublisher;
import com.comatching.user.domain.member.component.RandomNicknameGenerator;
import com.comatching.user.domain.member.dto.ProfileUpdateRequest;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.entity.Profile;
import com.comatching.user.domain.member.entity.ProfileHobby;
import com.comatching.user.domain.member.entity.ProfileTag;
import com.comatching.user.domain.member.repository.MemberRepository;
import com.comatching.user.domain.member.repository.ProfileRepository;
import com.comatching.user.global.config.ProfileImageProperties;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileServiceImpl 테스트")
class ProfileServiceImplTest {

	@InjectMocks
	private ProfileServiceImpl profileService;

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private ProfileRepository profileRepository;

	@Mock
	private UserEventPublisher eventPublisher;

	@Mock
	private ProfileImageProperties profileImageProperties;

	@Mock
	private RandomNicknameGenerator nicknameGenerator;

	@Nested
	@DisplayName("프로필 생성")
	class CreateProfile {

		@Test
		@DisplayName("태그가 포함된 프로필을 정상적으로 생성한다")
		void shouldCreateProfileWithTags() {
			// given
			Long memberId = 1L;
			Member member = createMember(memberId);
			ProfileCreateRequest request = ProfileCreateRequest.builder()
				.nickname("테스트유저")
				.gender(Gender.MALE)
				.birthDate(LocalDate.of(2000, 1, 1))
				.mbti("ENFP")
				.university("한국대학교")
				.major("컴퓨터공학과")
				.contactFrequency(ContactFrequency.FREQUENT)
				.hobbies(List.of(new HobbyDto(HobbyCategory.SPORTS, "축구")))
				.tags(List.of(
					new ProfileTagDto("EGG_FACE"),
					new ProfileTagDto("BRIGHT")
				))
				.build();

			given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
			given(profileImageProperties.baseUrl()).willReturn("https://img.com/");
			given(profileImageProperties.filenames()).willReturn(List.of("default.png"));
			given(profileRepository.save(any(Profile.class))).willAnswer(invocation -> invocation.getArgument(0));
			willDoNothing().given(eventPublisher).sendProfileUpdatedMatchingEvent(any());
			willDoNothing().given(eventPublisher).sendSignupEvent(any());

			// when
			ProfileResponse response = profileService.createProfile(memberId, request);

			// then
			assertThat(response).isNotNull();
			assertThat(response.tags()).hasSize(2);
			assertThat(response.tags().get(0).tag()).isEqualTo("EGG_FACE");
		}

		@Test
		@DisplayName("태그 없이 프로필을 생성할 수 있다")
		void shouldCreateProfileWithoutTags() {
			// given
			Long memberId = 1L;
			Member member = createMember(memberId);
			ProfileCreateRequest request = ProfileCreateRequest.builder()
				.nickname("테스트유저")
				.gender(Gender.MALE)
				.birthDate(LocalDate.of(2000, 1, 1))
				.mbti("ENFP")
				.university("한국대학교")
				.major("컴퓨터공학과")
				.contactFrequency(ContactFrequency.FREQUENT)
				.hobbies(List.of(new HobbyDto(HobbyCategory.SPORTS, "축구")))
				.tags(null)
				.build();

			given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
			given(profileImageProperties.baseUrl()).willReturn("https://img.com/");
			given(profileImageProperties.filenames()).willReturn(List.of("default.png"));
			given(profileRepository.save(any(Profile.class))).willAnswer(invocation -> invocation.getArgument(0));
			willDoNothing().given(eventPublisher).sendProfileUpdatedMatchingEvent(any());
			willDoNothing().given(eventPublisher).sendSignupEvent(any());

			// when
			ProfileResponse response = profileService.createProfile(memberId, request);

			// then
			assertThat(response).isNotNull();
			assertThat(response.tags()).isEmpty();
		}
	}

	@Nested
	@DisplayName("프로필 수정")
	class UpdateProfile {

		@Test
		@DisplayName("태그를 수정하면 기존 태그가 새 태그로 교체된다")
		void shouldUpdateTagsSuccessfully() {
			// given
			Long memberId = 1L;
			Profile profile = createProfileWithTags(memberId);

			ProfileUpdateRequest request = new ProfileUpdateRequest(
				null, null, null, null, null, null, null, null,
				null, null, null, null, null,
				List.of(new ProfileTagDto("SLIM"), new ProfileTagDto("MUSCULAR")),
				null
			);

			given(profileRepository.findByMemberId(memberId)).willReturn(Optional.of(profile));
			willDoNothing().given(eventPublisher).sendProfileUpdatedMatchingEvent(any());
			willDoNothing().given(eventPublisher).sendUpdateEvent(any());

			// when
			ProfileResponse response = profileService.updateProfile(memberId, request);

			// then
			assertThat(response.tags()).hasSize(2);
		}

		@Test
		@DisplayName("카테고리별 3개 초과 태그로 수정 시 예외가 발생한다")
		void shouldThrowWhenExceedingCategoryLimitOnUpdate() {
			// given
			Long memberId = 1L;
			Profile profile = createProfileWithTags(memberId);

			// 외모 카테고리 태그 4개 (FACE_SHAPE 그룹)
			ProfileUpdateRequest request = new ProfileUpdateRequest(
				null, null, null, null, null, null, null, null,
				null, null, null, null, null,
				List.of(
					new ProfileTagDto("EGG_FACE"),
					new ProfileTagDto("ANGULAR_FACE"),
					new ProfileTagDto("ROUND_FACE"),
					new ProfileTagDto("SHARP_FACE")
				),
				null
			);

			given(profileRepository.findByMemberId(memberId)).willReturn(Optional.of(profile));

			// when & then
			assertThatThrownBy(() -> profileService.updateProfile(memberId, request))
				.isInstanceOf(BusinessException.class);
		}
	}

	@Nested
	@DisplayName("프로필 조회")
	class GetProfile {

		@Test
		@DisplayName("프로필 조회 시 태그 목록이 포함된다")
		void shouldReturnProfileWithTags() {
			// given
			Long memberId = 1L;
			Profile profile = createProfileWithTags(memberId);

			given(profileRepository.findByMemberId(memberId)).willReturn(Optional.of(profile));

			// when
			ProfileResponse response = profileService.getProfile(memberId);

			// then
			assertThat(response).isNotNull();
			assertThat(response.tags()).isNotEmpty();
		}
	}

	private Member createMember(Long memberId) {
		return Member.builder()
			.email("test@test.com")
			.password("1234")
			.role(MemberRole.ROLE_GUEST)
			.status(MemberStatus.ACTIVE)
			.build();
	}

	private Profile createProfileWithTags(Long memberId) {
		Member member = createMember(memberId);

		List<ProfileHobby> hobbies = List.of(new ProfileHobby(HobbyCategory.SPORTS, "축구"));
		List<ProfileTag> tags = List.of(
			new ProfileTag(ProfileTagItem.EGG_FACE),
			new ProfileTag(ProfileTagItem.BRIGHT)
		);

		return Profile.builder()
			.member(member)
			.nickname("테스트유저")
			.gender(Gender.MALE)
			.birthDate(LocalDate.of(2000, 1, 1))
			.mbti("ENFP")
			.university("한국대학교")
			.major("컴퓨터공학과")
			.contactFrequency(ContactFrequency.FREQUENT)
			.hobbies(hobbies)
			.tags(tags)
			.build();
	}
}
