package com.comatching.matching.infra.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.handler.GlobalExceptionHandler;
import com.comatching.common.resolver.MemberInfoArgumentResolver;
import com.comatching.matching.domain.component.MatchingItemPolicy;
import com.comatching.matching.domain.component.MatchingProcessor;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.repository.history.MatchingHistoryRepository;
import com.comatching.matching.domain.service.MatchingHistoryService;
import com.comatching.matching.domain.service.MatchingServiceImpl;
import com.comatching.matching.infra.client.ItemClient;
import com.comatching.matching.infra.client.MemberClient;
import com.comatching.matching.infra.kafka.MatchingEventProducer;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("MatchingController 탈퇴 회원 API 테스트")
class MatchingControllerTest {

	private MockMvc mockMvc;

	@Mock private MatchingHistoryRepository historyRepository;
	@Mock private MemberClient memberClient;
	@Mock private ItemClient itemClient;
	@Mock private MatchingEventProducer matchingEventProducer;
	@Mock private MatchingItemPolicy matchingItemPolicy;
	@Mock private MatchingProcessor matchingProcessor;
	@Mock private MatchingHistoryService matchingHistoryService;

	@BeforeEach
	void setUp() {
		MatchingServiceImpl matchingService = new MatchingServiceImpl(
			historyRepository,
			memberClient,
			itemClient,
			matchingEventProducer,
			matchingItemPolicy,
			matchingProcessor
		);
		mockMvc = MockMvcBuilders.standaloneSetup(new MatchingController(matchingService, matchingHistoryService))
			.setCustomArgumentResolvers(new MemberInfoArgumentResolver())
			.setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
			.build();
	}

	@Test
	@DisplayName("POST /api/matching은 남아 있는 후보가 탈퇴 프로필이면 매칭 결과를 만들지 않는다")
	void shouldRejectWithdrawnCandidate() throws Exception {
		long memberId = 1L;
		long withdrawnMemberId = 2L;
		MatchingRequest request = new MatchingRequest(null, null, null, null, false, null);
		ProfileResponse myProfile = ProfileResponse.builder()
			.memberId(memberId)
			.gender(Gender.MALE)
			.birthDate(LocalDate.of(2000, 1, 1))
			.isMatchable(true)
			.build();
		MatchingCandidate staleCandidate = MatchingCandidate.create(
			withdrawnMemberId,
			10L,
			Gender.FEMALE,
			"ENFP",
			"디자인학과",
			ContactFrequency.FREQUENT,
			List.of(HobbyCategory.SPORTS),
			LocalDate.of(2000, 1, 1),
			true
		);
		ProfileResponse withdrawnProfile = ProfileResponse.builder()
			.memberId(withdrawnMemberId)
			.isMatchable(false)
			.build();

		given(memberClient.getProfile(memberId)).willReturn(myProfile);
		given(matchingItemPolicy.determine(request)).willReturn(List.of());
		given(matchingProcessor.process(memberId, myProfile, request)).willReturn(staleCandidate);
		given(memberClient.getProfile(withdrawnMemberId)).willReturn(withdrawnProfile);

		mockMvc.perform(post("/api/matching")
				.header("X-Member-Id", String.valueOf(memberId))
				.contentType("application/json")
				.content("{}"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("MATCH-001"));

		then(historyRepository).shouldHaveNoInteractions();
		then(matchingEventProducer).shouldHaveNoInteractions();
	}
}
