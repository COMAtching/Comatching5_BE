package com.comatching.member.domain.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.member.domain.entity.Member;
import com.comatching.member.domain.entity.Profile;
import com.comatching.member.domain.repository.MemberRepository;
import com.comatching.member.domain.repository.ProfileRepository;
import com.comatching.member.global.exception.MemberErrorCode;

@ExtendWith(MockitoExtension.class)
class ProfileServiceImplTest {

	@InjectMocks
	private ProfileServiceImpl profileService;

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private ProfileRepository profileRepository;

	@Test
	void createProfile_success() {
		// given
		Long memberId = 1L;
		ProfileCreateRequest request = new ProfileCreateRequest(
			"nickname", Gender.MALE, LocalDate.of(2026, 1, 6), "mbti", "intro", "imgUrl", null, null
		);

		Member member = Member.builder()
			.email("test@test.com")
			.role(MemberRole.ROLE_GUEST)
			.status(MemberStatus.ACTIVE)
			.build();
		ReflectionTestUtils.setField(member, "id", memberId);

		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));

		given(profileRepository.save(any(Profile.class))).willAnswer(invocation -> {
			Profile p = invocation.getArgument(0);
			ReflectionTestUtils.setField(p, "id", 100L);
			return p;
		});

		// when
		ProfileResponse response = profileService.createProfile(memberId, request);

		// then
		assertThat(response.memberId()).isEqualTo(memberId);
		assertThat(response.nickname()).isEqualTo(request.nickname());

		assertThat(member.getRole()).isEqualTo(MemberRole.ROLE_USER);

		verify(profileRepository, times(1)).save(any(Profile.class));
	}

	@Test
	void createProfile_fail_memberNotFound() {
		// given
		Long memberId = 999L;
		ProfileCreateRequest request = new ProfileCreateRequest(
			"nickname", Gender.MALE, LocalDate.of(2026, 1, 6), "mbti", "intro", "imgUrl", null, null
		);

		given(memberRepository.findById(memberId)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> profileService.createProfile(memberId, request))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(MemberErrorCode.USER_NOT_EXIST);

		verify(profileRepository, never()).save(any());
	}

	@Test
	void createProfile_fail_profileAlreadyExists() {
		// given
		Long memberId = 1L;
		ProfileCreateRequest request = new ProfileCreateRequest(
			"nickname", Gender.MALE, LocalDate.of(2026, 1, 6), "mbti", "intro", "imgUrl", null, null
		);

		Member member = Member.builder().build();
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