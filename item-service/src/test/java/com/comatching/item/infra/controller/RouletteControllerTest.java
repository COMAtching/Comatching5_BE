package com.comatching.item.infra.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.exception.handler.GlobalExceptionHandler;
import com.comatching.common.resolver.MemberInfoArgumentResolver;
import com.comatching.item.domain.roulette.dto.RouletteSpinResponse;
import com.comatching.item.domain.roulette.enums.RouletteType;
import com.comatching.item.domain.roulette.service.RouletteService;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("RouletteController API 계약 테스트")
class RouletteControllerTest {

	private MockMvc mockMvc;

	@Mock
	private RouletteService rouletteService;

	@InjectMocks
	private RouletteController rouletteController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(rouletteController)
			.setCustomArgumentResolvers(new MemberInfoArgumentResolver())
			.setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
			.build();
	}

	@Test
	@DisplayName("POST /api/roulette/spins는 회원과 룰렛 타입을 전달하고 보상명을 반환한다")
	void shouldSpinRouletteAndReturnRewardName() throws Exception {
		given(rouletteService.spinRoulette(any(MemberInfo.class), eq(RouletteType.PAID)))
			.willReturn(new RouletteSpinResponse("2만원권 상품권"));

		mockMvc.perform(post("/api/roulette/spins")
				.param("rouletteType", "PAID")
				.header("X-Member-Id", "1")
				.header("X-Member-Email", "member@example.com")
				.header("X-Member-Role", "USER"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("GEN-000"))
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.rewardName").value("2만원권 상품권"));

		ArgumentCaptor<MemberInfo> memberCaptor = ArgumentCaptor.forClass(MemberInfo.class);
		then(rouletteService).should().spinRoulette(memberCaptor.capture(), eq(RouletteType.PAID));
		assertThat(memberCaptor.getValue().memberId()).isEqualTo(1L);
		assertThat(memberCaptor.getValue().email()).isEqualTo("member@example.com");
		assertThat(memberCaptor.getValue().role()).isEqualTo("USER");
	}

	@Test
	@DisplayName("rouletteType 요청 파라미터가 없으면 400을 반환한다")
	void shouldRejectMissingRouletteType() throws Exception {
		mockMvc.perform(post("/api/roulette/spins")
				.header("X-Member-Id", "1"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(GeneralErrorCode.MISSING_REQUEST_PARAMETER.getCode()));

		then(rouletteService).shouldHaveNoInteractions();
	}
}
