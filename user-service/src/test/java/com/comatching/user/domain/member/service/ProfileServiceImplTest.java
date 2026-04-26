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
import org.springframework.test.util.ReflectionTestUtils;

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
import com.comatching.common.service.S3Service;
import com.comatching.user.domain.event.UserEventPublisher;
import com.comatching.user.domain.member.dto.ProfileUpdateRequest;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.entity.Profile;
import com.comatching.user.domain.member.entity.ProfileHobby;
import com.comatching.user.domain.member.entity.ProfileTag;
import com.comatching.user.domain.member.repository.MemberRepository;
import com.comatching.user.domain.member.repository.ProfileRepository;
import com.comatching.user.global.config.ProfileImageProperties;
import com.comatching.user.global.exception.UserErrorCode;

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
	private S3Service s3Service;

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
				.hobbies(List.of(
					new HobbyDto(HobbyCategory.SPORTS, "축구"),
					new HobbyDto(HobbyCategory.CULTURE, "영화감상")
				))
				.tags(List.of(
					new ProfileTagDto("EGG_FACE"),
					new ProfileTagDto("BRIGHT")
				))
				.build();

			given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
			given(profileImageProperties.baseUrl()).willReturn("https://img.com/");
			given(profileRepository.save(any(Profile.class))).willAnswer(invocation -> invocation.getArgument(0));
			willDoNothing().given(eventPublisher).sendProfileUpdatedMatchingEvent(any());
			willDoNothing().given(eventPublisher).sendSignupEvent(any());

			// when
			ProfileResponse response = profileService.createProfile(memberId, request);

			// then
			assertThat(response).isNotNull();
			assertThat(response.tags()).hasSize(2);
			assertThat(response.tags()).extracting(ProfileTagDto::tag)
				.containsExactly("계란형 얼굴", "밝은 분위기");
		}

		@Test
		@DisplayName("한글 라벨 태그로 프로필을 생성할 수 있다")
		void shouldCreateProfileWithKoreanTagLabels() {
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
				.hobbies(List.of(
					new HobbyDto(HobbyCategory.SPORTS, "축구"),
					new HobbyDto(HobbyCategory.CULTURE, "영화감상")
				))
				.tags(List.of(
					new ProfileTagDto("밝은 분위기"),
					new ProfileTagDto("경청형")
				))
				.build();

			given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
			given(profileImageProperties.baseUrl()).willReturn("https://img.com/");
			given(profileRepository.save(any(Profile.class))).willAnswer(invocation -> invocation.getArgument(0));
			willDoNothing().given(eventPublisher).sendProfileUpdatedMatchingEvent(any());
			willDoNothing().given(eventPublisher).sendSignupEvent(any());

			// when
			ProfileResponse response = profileService.createProfile(memberId, request);

			// then
			assertThat(response).isNotNull();
			assertThat(response.tags()).hasSize(2);
			assertThat(response.tags()).extracting(ProfileTagDto::tag)
				.containsExactly("밝은 분위기", "경청형");
		}

		@Test
		@DisplayName("유효하지 않은 한글 태그로 생성 시 예외가 발생한다")
		void shouldThrowWhenCreatingProfileWithInvalidKoreanTag() {
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
				.hobbies(List.of(
					new HobbyDto(HobbyCategory.SPORTS, "축구"),
					new HobbyDto(HobbyCategory.CULTURE, "영화감상")
				))
				.tags(List.of(new ProfileTagDto("없는 태그")))
				.build();

			given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
			given(profileImageProperties.baseUrl()).willReturn("https://img.com/");

			// when & then
			assertThatThrownBy(() -> profileService.createProfile(memberId, request))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(UserErrorCode.INVALID_PROFILE_TAG);
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
				.hobbies(List.of(
					new HobbyDto(HobbyCategory.SPORTS, "축구"),
					new HobbyDto(HobbyCategory.CULTURE, "영화감상")
				))
				.tags(null)
				.build();

			given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
			given(profileImageProperties.baseUrl()).willReturn("https://img.com/");
			given(profileRepository.save(any(Profile.class))).willAnswer(invocation -> invocation.getArgument(0));
			willDoNothing().given(eventPublisher).sendProfileUpdatedMatchingEvent(any());
			willDoNothing().given(eventPublisher).sendSignupEvent(any());

			// when
			ProfileResponse response = profileService.createProfile(memberId, request);

			// then
			assertThat(response).isNotNull();
			assertThat(response.tags()).isEmpty();
		}

		@Test
		@DisplayName("프로필 이미지 값이 S3 key면 퍼블릭 URL로 변환해 저장한다")
		void shouldConvertS3KeyToPublicUrlWhenCreatingProfile() {
			// given
			Long memberId = 1L;
			Member member = createMember(memberId);
			String imageKey = "profiles/1/test.png";
			String imageUrl = "https://bucket.s3.ap-northeast-2.amazonaws.com/profiles/1/test.png";
			ProfileCreateRequest request = ProfileCreateRequest.builder()
				.nickname("테스트유저")
				.gender(Gender.MALE)
				.birthDate(LocalDate.of(2000, 1, 1))
				.mbti("ENFP")
				.university("한국대학교")
				.major("컴퓨터공학과")
				.contactFrequency(ContactFrequency.FREQUENT)
				.profileImageKey(imageKey)
				.hobbies(List.of(
					new HobbyDto(HobbyCategory.SPORTS, "축구"),
					new HobbyDto(HobbyCategory.CULTURE, "영화감상")
				))
				.tags(null)
				.build();

			given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
			given(s3Service.getFileUrl(imageKey)).willReturn(imageUrl);
			given(profileRepository.save(any(Profile.class))).willAnswer(invocation -> invocation.getArgument(0));
			willDoNothing().given(eventPublisher).sendProfileUpdatedMatchingEvent(any());
			willDoNothing().given(eventPublisher).sendSignupEvent(any());

			// when
			ProfileResponse response = profileService.createProfile(memberId, request);

			// then
			assertThat(response.profileImageUrl()).isEqualTo(imageUrl);
		}

		@Test
		@DisplayName("프로필 이미지 값이 default_동물이름이면 해당 기본 이미지 URL을 저장한다")
		void shouldUseAnimalDefaultImageWhenCreatingProfile() {
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
				.profileImageKey("default_dog")
				.hobbies(List.of(
					new HobbyDto(HobbyCategory.SPORTS, "축구"),
					new HobbyDto(HobbyCategory.CULTURE, "영화감상")
				))
				.tags(null)
				.build();

			given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
			given(profileImageProperties.baseUrl()).willReturn("https://img.com/defaults/profile/");
			given(profileRepository.save(any(Profile.class))).willAnswer(invocation -> invocation.getArgument(0));
			willDoNothing().given(eventPublisher).sendProfileUpdatedMatchingEvent(any());
			willDoNothing().given(eventPublisher).sendSignupEvent(any());

			// when
			ProfileResponse response = profileService.createProfile(memberId, request);

			// then
			assertThat(response.profileImageUrl()).isEqualTo("https://img.com/defaults/profile/dog_male%201.png");
		}

		@Test
		@DisplayName("닉네임이 중복되면 프로필 생성 시 예외가 발생한다")
		void shouldThrowWhenNicknameDuplicatedOnCreate() {
			// given
			Long memberId = 1L;
			Member member = createMember(memberId);
			ProfileCreateRequest request = ProfileCreateRequest.builder()
				.nickname("중복닉네임")
				.gender(Gender.MALE)
				.birthDate(LocalDate.of(2000, 1, 1))
				.mbti("ENFP")
				.university("한국대학교")
				.major("컴퓨터공학과")
				.contactFrequency(ContactFrequency.FREQUENT)
				.hobbies(List.of(
					new HobbyDto(HobbyCategory.SPORTS, "축구"),
					new HobbyDto(HobbyCategory.CULTURE, "영화감상")
				))
				.tags(null)
				.build();

			given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
			given(profileRepository.existsByNickname("중복닉네임")).willReturn(true);

			// when & then
			assertThatThrownBy(() -> profileService.createProfile(memberId, request))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(UserErrorCode.DUPLICATE_NICKNAME);
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
			assertThat(response.tags()).extracting(ProfileTagDto::tag)
				.containsExactly("마른 체형", "근육질");
		}

		@Test
		@DisplayName("전체 5개 초과 태그로 수정 시 예외가 발생한다")
		void shouldThrowWhenExceedingTotalTagLimitOnUpdate() {
			// given
			Long memberId = 1L;
			Profile profile = createProfileWithTags(memberId);

			// 총 6개 태그
			ProfileUpdateRequest request = new ProfileUpdateRequest(
				null, null, null, null, null, null, null, null,
				null, null, null, null, null,
				List.of(
					new ProfileTagDto("EGG_FACE"),
					new ProfileTagDto("DIMPLE"),
					new ProfileTagDto("FAIR_SKIN"),
					new ProfileTagDto("EXTROVERT"),
					new ProfileTagDto("CARING"),
					new ProfileTagDto("LOGICAL")
				),
				null
			);

			given(profileRepository.findByMemberId(memberId)).willReturn(Optional.of(profile));

			// when & then
			assertThatThrownBy(() -> profileService.updateProfile(memberId, request))
				.isInstanceOf(BusinessException.class);
		}

		@Test
		@DisplayName("프로필 이미지 값이 default면 기본 프로필 이미지로 변경된다")
		void shouldSetDefaultProfileImageOnUpdateWhenDefaultValueProvided() {
			// given
			Long memberId = 1L;
			Profile profile = createProfileWithTags(memberId);
			ProfileUpdateRequest request = new ProfileUpdateRequest(
				null, null, null, "default", null, null, null, null,
				null, null, null, null, null, null, null
			);

			given(profileRepository.findByMemberId(memberId)).willReturn(Optional.of(profile));
			given(profileImageProperties.baseUrl()).willReturn("https://img.com/defaults/profile/");
			willDoNothing().given(eventPublisher).sendProfileUpdatedMatchingEvent(any());
			willDoNothing().given(eventPublisher).sendUpdateEvent(any());

			// when
			ProfileResponse response = profileService.updateProfile(memberId, request);

			// then
			assertThat(response.profileImageUrl()).isEqualTo("https://img.com/defaults/profile/dog_male%201.png");
		}

		@Test
		@DisplayName("프로필 이미지 값이 default_동물이름이고 성별이 FEMALE이면 female 이미지를 사용한다")
		void shouldUseFemaleAnimalImageOnUpdateWhenGenderIsFemale() {
			// given
			Long memberId = 1L;
			Profile profile = createProfileWithTags(memberId);
			ProfileUpdateRequest request = new ProfileUpdateRequest(
				null, null, null, "default_fox", Gender.FEMALE, null, null, null,
				null, null, null, null, null, null, null
			);

			given(profileRepository.findByMemberId(memberId)).willReturn(Optional.of(profile));
			given(profileImageProperties.baseUrl()).willReturn("https://img.com/defaults/profile/");
			willDoNothing().given(eventPublisher).sendProfileUpdatedMatchingEvent(any());
			willDoNothing().given(eventPublisher).sendUpdateEvent(any());

			// when
			ProfileResponse response = profileService.updateProfile(memberId, request);

			// then
			assertThat(response.profileImageUrl()).isEqualTo("https://img.com/defaults/profile/fox_female%201.png");
		}
	}

	@Nested
	@DisplayName("닉네임 중복 확인")
	class NicknameAvailability {

		@Test
		@DisplayName("중복 닉네임이면 사용 불가를 반환한다")
		void shouldReturnFalseWhenNicknameDuplicated() {
			// given
			given(profileRepository.existsByNickname("중복닉네임")).willReturn(true);

			// when
			boolean available = profileService.isNicknameAvailable("중복닉네임");

			// then
			assertThat(available).isFalse();
		}

		@Test
		@DisplayName("미사용 닉네임이면 사용 가능을 반환한다")
		void shouldReturnTrueWhenNicknameAvailable() {
			// given
			given(profileRepository.existsByNickname("신규닉네임")).willReturn(false);

			// when
			boolean available = profileService.isNicknameAvailable("신규닉네임");

			// then
			assertThat(available).isTrue();
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
			assertThat(response.tags()).extracting(ProfileTagDto::tag)
				.containsExactly("계란형 얼굴", "밝은 분위기");
		}

		@Test
		@DisplayName("bulk 프로필 조회 전에 취미 목록을 한 번에 초기화한다")
		void shouldFetchHobbiesWhenGettingProfilesBulk() {
			// given
			List<Long> memberIds = List.of(1L, 2L);
			Profile firstProfile = createProfileWithTags(1L);
			Profile secondProfile = createProfileWithTags(2L);

			given(profileRepository.findAllByMemberIdIn(memberIds))
				.willReturn(List.of(firstProfile, secondProfile));
			given(profileRepository.findAllWithHobbiesByMemberIdIn(memberIds))
				.willReturn(List.of(firstProfile, secondProfile));

			// when
			List<ProfileResponse> responses = profileService.getProfilesByIds(memberIds);

			// then
			assertThat(responses).hasSize(2);
			assertThat(responses)
				.allSatisfy(response -> assertThat(response.hobbies()).hasSize(2));
			then(profileRepository).should().findAllWithHobbiesByMemberIdIn(memberIds);
		}

		@Test
		@DisplayName("bulk 프로필 조회 결과가 비어 있으면 취미 초기화 쿼리를 실행하지 않는다")
		void shouldSkipHobbyFetchWhenBulkProfilesEmpty() {
			// given
			List<Long> memberIds = List.of(1L, 2L);
			given(profileRepository.findAllByMemberIdIn(memberIds)).willReturn(List.of());

			// when
			List<ProfileResponse> responses = profileService.getProfilesByIds(memberIds);

			// then
			assertThat(responses).isEmpty();
			then(profileRepository).should(never()).findAllWithHobbiesByMemberIdIn(anyList());
		}
	}

	private Member createMember(Long memberId) {
		Member member = Member.builder()
			.email("test@test.com")
			.password("1234")
			.role(MemberRole.ROLE_GUEST)
			.status(MemberStatus.ACTIVE)
			.build();
		ReflectionTestUtils.setField(member, "id", memberId);
		return member;
	}

	private Profile createProfileWithTags(Long memberId) {
		Member member = createMember(memberId);

		List<ProfileHobby> hobbies = List.of(
			new ProfileHobby(HobbyCategory.SPORTS, "축구"),
			new ProfileHobby(HobbyCategory.CULTURE, "영화감상")
		);
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
