package com.comatching.member.domain.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.util.ArrayList;
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
import com.comatching.common.dto.member.HobbyDto;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.member.domain.entity.Member;
import com.comatching.member.domain.entity.Profile;
import com.comatching.member.domain.entity.ProfileHobby;
import com.comatching.member.domain.repository.MemberRepository;
import com.comatching.member.domain.repository.ProfileRepository;
import com.comatching.member.domain.service.profile.ProfileServiceImpl;
import com.comatching.member.global.exception.MemberErrorCode;
import com.comatching.member.infra.kafka.MemberEventProducer;

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
	private MemberEventProducer memberEventProducer;

	private ProfileCreateRequest createValidRequest() {
		List<HobbyDto> hobbies = List.of(
			new HobbyDto(HobbyCategory.SPORTS, "축구")
		);
		return ProfileCreateRequest.builder()
			.nickname("테스트닉네임")
			.gender(Gender.MALE)
			.birthDate(LocalDate.of(2000, 1, 1))
			.mbti("ISTJ")
			.intro("안녕하세요")
			.profileImageKey("profiles/test.jpg")
			.university("테스트대학교")
			.major("컴퓨터공학과")
			.contactFrequency(ContactFrequency.FREQUENT)
			.hobbies(hobbies)
			.build();
	}

	private Member createGuestMember(Long id) {
		Member member = Member.builder()
			.email("test@test.com")
			.role(MemberRole.ROLE_GUEST)
			.status(MemberStatus.ACTIVE)
			.build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	@Nested
	@DisplayName("createProfile 메서드")
	class CreateProfile {

		@Test
		@DisplayName("유효한 요청으로 프로필을 생성하면 프로필 응답을 반환하고 회원 역할을 USER로 변경한다")
		void shouldCreateProfileAndChangeRoleToUser() {
			// given
			Long memberId = 1L;
			ProfileCreateRequest request = createValidRequest();
			Member member = createGuestMember(memberId);

			given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
			given(profileRepository.save(any(Profile.class))).willAnswer(invocation -> {
				Profile p = invocation.getArgument(0);
				ReflectionTestUtils.setField(p, "id", 100L);
				// Profile의 hobbies와 intros가 null일 경우 빈 리스트로 설정
				if (p.getHobbies() == null) {
					ReflectionTestUtils.setField(p, "hobbies", new ArrayList<ProfileHobby>());
				}
				if (p.getIntros() == null) {
					ReflectionTestUtils.setField(p, "intros", new ArrayList<>());
				}
				return p;
			});

			// when
			ProfileResponse response = profileService.createProfile(memberId, request);

			// then
			assertThat(response.memberId()).isEqualTo(memberId);
			assertThat(response.nickname()).isEqualTo(request.nickname());
			assertThat(member.getRole()).isEqualTo(MemberRole.ROLE_USER);
			verify(profileRepository).save(any(Profile.class));
			verify(memberEventProducer).sendProfileUpdatedMatchingEvent(any());
			verify(memberEventProducer).sendSignupEvent(any());
		}

		@Test
		@DisplayName("존재하지 않는 회원 ID로 프로필을 생성하면 USER_NOT_EXIST 예외를 던진다")
		void shouldThrowExceptionWhenMemberNotFound() {
			// given
			Long memberId = 999L;
			ProfileCreateRequest request = createValidRequest();

			given(memberRepository.findById(memberId)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> profileService.createProfile(memberId, request))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(MemberErrorCode.USER_NOT_EXIST);

			verify(profileRepository, never()).save(any());
		}

		@Test
		@DisplayName("이미 프로필이 존재하는 회원이 프로필을 생성하면 PROFILE_ALREADY_EXISTS 예외를 던진다")
		void shouldThrowExceptionWhenProfileAlreadyExists() {
			// given
			Long memberId = 1L;
			ProfileCreateRequest request = createValidRequest();

			Member member = createGuestMember(memberId);
			Profile existingProfile = Profile.builder().member(member).build();
			ReflectionTestUtils.setField(member, "profile", existingProfile);

			given(memberRepository.findById(memberId)).willReturn(Optional.of(member));

			// when & then
			assertThatThrownBy(() -> profileService.createProfile(memberId, request))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(MemberErrorCode.PROFILE_ALREADY_EXISTS);

			verify(profileRepository, never()).save(any());
		}
	}
}
