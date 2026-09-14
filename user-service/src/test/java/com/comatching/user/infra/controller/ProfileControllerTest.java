package com.comatching.user.infra.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.service.S3Service;
import com.comatching.user.domain.event.UserEventPublisher;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.entity.Profile;
import com.comatching.user.domain.member.repository.MemberRepository;
import com.comatching.user.domain.member.repository.ProfileRepository;
import com.comatching.user.domain.member.service.EnumLookupService;
import com.comatching.user.domain.member.service.ProfileServiceImpl;
import com.comatching.user.global.config.ProfileImageProperties;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileController 사전가입 이벤트 API 테스트")
class ProfileControllerTest {

	private MockMvc mockMvc;

	@Mock private MemberRepository memberRepository;
	@Mock private ProfileRepository profileRepository;
	@Mock private UserEventPublisher eventPublisher;
	@Mock private ProfileImageProperties profileImageProperties;
	@Mock private S3Service s3Service;
	@Mock private EnumLookupService enumLookupService;

	@BeforeEach
	void setUp() {
		ProfileServiceImpl profileService = new ProfileServiceImpl(
			memberRepository,
			profileRepository,
			eventPublisher,
			profileImageProperties,
			s3Service
		);
		mockMvc = MockMvcBuilders.standaloneSetup(
			new ProfileController(profileService, profileService, enumLookupService)
		).build();
	}

	@Test
	@DisplayName("POST /api/internal/users/profile은 프로필을 생성해도 member-signup 이벤트를 발행하지 않는다")
	void shouldCreateProfileWithoutPreSignupEvent() throws Exception {
		Member member = Member.builder()
			.email("member@example.com")
			.password("password")
			.role(MemberRole.ROLE_GUEST)
			.status(MemberStatus.ACTIVE)
			.build();
		ReflectionTestUtils.setField(member, "id", 1L);
		given(memberRepository.findById(1L)).willReturn(Optional.of(member));
		given(profileImageProperties.baseUrl()).willReturn("https://img.example.com/");
		given(profileRepository.save(any(Profile.class))).willAnswer(invocation -> invocation.getArgument(0));

		mockMvc.perform(post("/api/internal/users/profile")
				.header("X-Member-Id", "1")
				.contentType("application/json")
				.content("""
					{
					  "nickname": "테스트유저",
					  "gender": "MALE",
					  "birthDate": "2000-01-01",
					  "mbti": "ENFP",
					  "university": "한국대학교",
					  "major": "컴퓨터공학과",
					  "contactFrequency": "FREQUENT",
					  "hobbies": [
					    {"category": "SPORTS", "name": "축구"},
					    {"category": "CULTURE", "name": "영화감상"}
					  ],
					  "tags": []
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.memberId").value(1))
			.andExpect(jsonPath("$.isMatchable").value(true));

		then(eventPublisher).should().sendProfileUpdatedMatchingEvent(any());
		then(eventPublisher).should(never()).sendSignupEvent(any());
	}
}
