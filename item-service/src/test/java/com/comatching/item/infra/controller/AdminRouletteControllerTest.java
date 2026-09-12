package com.comatching.item.infra.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
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

import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.exception.handler.GlobalExceptionHandler;
import com.comatching.common.resolver.MemberInfoArgumentResolver;
import com.comatching.item.domain.roulette.dto.response.AdminGiftCardWinnerResponse;
import com.comatching.item.domain.roulette.enums.RouletteType;
import com.comatching.item.domain.admin.service.AdminRouletteService;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminRouletteController API 계약 테스트")
class AdminRouletteControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AdminRouletteService adminRouletteService;

    @InjectMocks
    private AdminRouletteController adminRouletteController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(adminRouletteController)
            .setCustomArgumentResolvers(new MemberInfoArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
            .build();
    }

    @Test
    @DisplayName("GET /api/v1/admin/roulette/gift-cards/unpaid는 미지급 상품권 당첨자 목록을 반환한다")
    void shouldReturnUnpaidGiftCardWinners() throws Exception {
        LocalDateTime participatedAt = LocalDateTime.of(2026, 9, 10, 12, 30);
        given(adminRouletteService.getUnpaidGiftCardWinners()).willReturn(List.of(
            new AdminGiftCardWinnerResponse(
                101L,
                1L,
                "winner@example.com",
                "당첨자",
                "행운",
                "2만원권 상품권",
                RouletteType.SPECIAL,
                participatedAt
            )
        ));

        mockMvc.perform(get("/api/v1/admin/roulette/gift-cards/unpaid")
                .header("X-Member-Id", "900")
                .header("X-Member-Email", "admin@example.com")
                .header("X-Member-Role", "ROLE_ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("GEN-000"))
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data[0].historyId").value(101))
            .andExpect(jsonPath("$.data[0].memberId").value(1))
            .andExpect(jsonPath("$.data[0].email").value("winner@example.com"))
            .andExpect(jsonPath("$.data[0].realName").value("당첨자"))
            .andExpect(jsonPath("$.data[0].nickname").value("행운"))
            .andExpect(jsonPath("$.data[0].rewardName").value("2만원권 상품권"))
            .andExpect(jsonPath("$.data[0].rouletteType").value("SPECIAL"))
            .andExpect(jsonPath("$.data[0].participatedAt[0]").value(2026))
            .andExpect(jsonPath("$.data[0].participatedAt[1]").value(9))
            .andExpect(jsonPath("$.data[0].participatedAt[2]").value(10))
            .andExpect(jsonPath("$.data[0].participatedAt[3]").value(12))
            .andExpect(jsonPath("$.data[0].participatedAt[4]").value(30));

        then(adminRouletteService).should().getUnpaidGiftCardWinners();
    }

    @Test
    @DisplayName("GET 미지급 상품권 당첨자 조회는 관리자 회원 헤더를 해석한다")
    void shouldResolveAdminMemberHeaders() throws Exception {
        given(adminRouletteService.getUnpaidGiftCardWinners()).willReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/roulette/gift-cards/unpaid")
                .header("X-Member-Id", "900")
                .header("X-Member-Email", "admin@example.com")
                .header("X-Member-Role", "ROLE_ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data").isEmpty());

        then(adminRouletteService).should().getUnpaidGiftCardWinners();
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/roulette/gift-cards/{historyId}/grant는 상품권 지급 완료를 위임한다")
    void shouldMarkGiftCardAsGranted() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/roulette/gift-cards/{historyId}/grant", 101L)
                .header("X-Member-Id", "900")
                .header("X-Member-Email", "admin@example.com")
                .header("X-Member-Role", "ROLE_ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("GEN-000"))
            .andExpect(jsonPath("$.status").value(200));

        then(adminRouletteService).should().markGiftCardAsGranted(101L);
    }

    @Test
    @DisplayName("상품권 지급 완료 경로의 historyId가 숫자가 아니면 400을 반환한다")
    void shouldRejectInvalidHistoryId() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/roulette/gift-cards/not-a-number/grant")
                .header("X-Member-Id", "900")
                .header("X-Member-Email", "admin@example.com")
                .header("X-Member-Role", "ROLE_ADMIN"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(GeneralErrorCode.TYPE_MISMATCH.getCode()));

        then(adminRouletteService).shouldHaveNoInteractions();
    }
}
