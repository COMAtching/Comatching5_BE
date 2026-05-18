package com.comatching.chat.infra.controller;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.comatching.chat.domain.dto.ChatMemberProfileResponse;
import com.comatching.chat.domain.service.profile.ChatProfileService;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.domain.enums.SocialAccountType;
import com.comatching.common.dto.member.HobbyDto;
import com.comatching.common.dto.member.ProfileTagDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.exception.handler.GlobalExceptionHandler;
import com.comatching.common.resolver.MemberInfoArgumentResolver;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ChatProfileControllerTest {

	private MockMvc mockMvc;

	@Mock
	private ChatProfileService chatProfileService;

	@InjectMocks
	private ChatProfileController chatProfileController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(chatProfileController)
			.setCustomArgumentResolvers(new MemberInfoArgumentResolver())
			.setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
			.build();
	}

	@Test
	@DisplayName("GET /api/chat/members/{memberId}/profile - 채팅용 프로필을 반환한다")
	void getMemberProfile_success() throws Exception {
		// given
		Long currentMemberId = 1L;
		Long targetMemberId = 2L;
		Long historyId = 100L;
		given(chatProfileService.getMemberProfile(currentMemberId, targetMemberId))
			.willReturn(new ChatMemberProfileResponse(
				targetMemberId,
					"상대닉네임",
					"https://img.example/profile.png",
					"컴퓨터공학과",
					24,
					"ENFP",
					"자주",
					List.of(new HobbyDto(HobbyCategory.SPORTS, "축구")),
					List.of(new ProfileTagDto("밝은 분위기")),
					"좋은 노래",
					"안녕하세요",
					SocialAccountType.INSTAGRAM,
					"comatching.official",
					historyId,
					true
				));

		// when & then
		mockMvc.perform(get("/api/chat/members/{memberId}/profile", targetMemberId)
				.header("X-Member-Id", currentMemberId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("GEN-000"))
			.andExpect(jsonPath("$.data.memberId").value(targetMemberId))
			.andExpect(jsonPath("$.data.nickname").value("상대닉네임"))
				.andExpect(jsonPath("$.data.profileImageUrl").value("https://img.example/profile.png"))
				.andExpect(jsonPath("$.data.major").value("컴퓨터공학과"))
				.andExpect(jsonPath("$.data.age").value(24))
				.andExpect(jsonPath("$.data.mbti").value("ENFP"))
				.andExpect(jsonPath("$.data.contactFrequency").value("자주"))
				.andExpect(jsonPath("$.data.hobbies[0].category").value("SPORTS"))
				.andExpect(jsonPath("$.data.hobbies[0].name").value("축구"))
				.andExpect(jsonPath("$.data.tags[0].tag").value("밝은 분위기"))
				.andExpect(jsonPath("$.data.song").value("좋은 노래"))
				.andExpect(jsonPath("$.data.intro").value("안녕하세요"))
				.andExpect(jsonPath("$.data.socialType").value("INSTAGRAM"))
				.andExpect(jsonPath("$.data.socialAccountId").value("comatching.official"))
				.andExpect(jsonPath("$.data.historyId").value(historyId))
				.andExpect(jsonPath("$.data.favorite").value(true))
				.andExpect(jsonPath("$.data.university").doesNotExist())
				.andExpect(jsonPath("$.data.birthDate").doesNotExist())
				.andExpect(jsonPath("$.data.gender").doesNotExist())
				.andExpect(jsonPath("$.data.email").doesNotExist());
	}

	@Test
	@DisplayName("GET /api/chat/members/{memberId}/profile - 프로필이 없으면 404를 반환한다")
	void getMemberProfile_notFound() throws Exception {
		// given
		Long currentMemberId = 1L;
		Long targetMemberId = 2L;
		given(chatProfileService.getMemberProfile(currentMemberId, targetMemberId))
			.willThrow(new BusinessException(GeneralErrorCode.NOT_FOUND));

		// when & then
		mockMvc.perform(get("/api/chat/members/{memberId}/profile", targetMemberId)
				.header("X-Member-Id", currentMemberId))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(GeneralErrorCode.NOT_FOUND.getCode()));
	}
}
