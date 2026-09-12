package com.comatching.item.infra.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.comatching.item.domain.roulette.dto.response.RoulettePageResponse;
import com.comatching.item.domain.roulette.dto.response.RouletteSpinResponse;
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
	@DisplayName("POST /api/items/roulette/{rouletteType}/spins는 회원과 룰렛 타입을 전달하고 보상명을 반환한다")
	void shouldSpinRouletteAndReturnRewardName() throws Exception {
		given(rouletteService.spinRoulette(any(MemberInfo.class), eq(RouletteType.SPECIAL)))
			.willReturn(new RouletteSpinResponse("2만원권 상품권"));

		mockMvc.perform(post("/api/items/roulette/SPECIAL/spins")
				.header("X-Member-Id", "1")
				.header("X-Member-Email", "member@example.com")
				.header("X-Member-Role", "USER"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("GEN-000"))
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.rewardName").value("2만원권 상품권"));

		ArgumentCaptor<MemberInfo> memberCaptor = ArgumentCaptor.forClass(MemberInfo.class);
		then(rouletteService).should().spinRoulette(memberCaptor.capture(), eq(RouletteType.SPECIAL));
		assertThat(memberCaptor.getValue().memberId()).isEqualTo(1L);
		assertThat(memberCaptor.getValue().email()).isEqualTo("member@example.com");
		assertThat(memberCaptor.getValue().role()).isEqualTo("USER");
	}

	@Test
	@DisplayName("지원하지 않는 rouletteType 경로 값이면 400을 반환한다")
	void shouldRejectUnsupportedRouletteType() throws Exception {
		mockMvc.perform(post("/api/items/roulette/PAID/spins")
				.header("X-Member-Id", "1"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(GeneralErrorCode.TYPE_MISMATCH.getCode()));

		then(rouletteService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("GET /api/items/roulette는 무료·스페셜 룰렛 참여 여부와 오늘 결제액을 반환한다")
	void shouldReturnRoulettePage() throws Exception {
		given(rouletteService.roulettePage(any(MemberInfo.class)))
			.willReturn(new RoulettePageResponse(true, false, 3500L));

		mockMvc.perform(get("/api/items/roulette")
				.header("X-Member-Id", "1")
				.header("X-Member-Email", "member@example.com")
				.header("X-Member-Role", "USER"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("GEN-000"))
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.isFreeParticipated").value(true))
			.andExpect(jsonPath("$.data.isSpecialParticipated").value(false))
			.andExpect(jsonPath("$.data.totalPay").value(3500));

		ArgumentCaptor<MemberInfo> memberCaptor = ArgumentCaptor.forClass(MemberInfo.class);
		then(rouletteService).should().roulettePage(memberCaptor.capture());
		assertThat(memberCaptor.getValue().memberId()).isEqualTo(1L);
		assertThat(memberCaptor.getValue().email()).isEqualTo("member@example.com");
		assertThat(memberCaptor.getValue().role()).isEqualTo("USER");
	}

	@Test
	@DisplayName("GET /api/items/roulette는 두 룰렛의 참여 여부를 독립적으로 반환한다")
	void shouldReturnEachRouletteParticipationStatus() throws Exception {
		given(rouletteService.roulettePage(any(MemberInfo.class)))
			.willReturn(new RoulettePageResponse(false, true, 3499L));

		mockMvc.perform(get("/api/items/roulette")
				.header("X-Member-Id", "1")
				.header("X-Member-Email", "member@example.com")
				.header("X-Member-Role", "USER"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("GEN-000"))
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.isFreeParticipated").value(false))
			.andExpect(jsonPath("$.data.isSpecialParticipated").value(true))
			.andExpect(jsonPath("$.data.totalPay").value(3499));

		ArgumentCaptor<MemberInfo> memberCaptor = ArgumentCaptor.forClass(MemberInfo.class);
		then(rouletteService).should().roulettePage(memberCaptor.capture());
		assertThat(memberCaptor.getValue().memberId()).isEqualTo(1L);
		assertThat(memberCaptor.getValue().email()).isEqualTo("member@example.com");
		assertThat(memberCaptor.getValue().role()).isEqualTo("USER");
	}

	@Test
	@DisplayName("GET /api/items/roulette는 rouletteType 쿼리 파라미터를 요구하지 않는다")
	void shouldNotRequireRouletteTypeOnRoulettePage() throws Exception {
		given(rouletteService.roulettePage(any(MemberInfo.class)))
			.willReturn(new RoulettePageResponse(false, false, 0L));

		mockMvc.perform(get("/api/items/roulette")
				.header("X-Member-Id", "1")
				.header("X-Member-Email", "member@example.com")
				.header("X-Member-Role", "USER"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.isFreeParticipated").value(false))
			.andExpect(jsonPath("$.data.isSpecialParticipated").value(false))
			.andExpect(jsonPath("$.data.totalPay").value(0));
	}
}
