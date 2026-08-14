package com.comatching.user.infra.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.common.exception.handler.GlobalExceptionHandler;
import com.comatching.common.resolver.MemberInfoArgumentResolver;
import com.comatching.user.domain.admin.dto.AdminUserSummaryResponse;
import com.comatching.user.domain.admin.service.AdminMemberService;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AdminMemberControllerTest {

	private MockMvc mockMvc;

	@Mock
	private AdminMemberService adminMemberService;

	@InjectMocks
	private AdminMemberController adminMemberController;

	private static final Long ADMIN_ID = 999L;
	private static final String SUCCESS_CODE = "GEN-000";

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(adminMemberController)
			.setCustomArgumentResolvers(new MemberInfoArgumentResolver(), new PageableHandlerMethodArgumentResolver())
			.setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
			.build();
	}

	@Test
	@DisplayName("GET /api/admin/users - 사용자 목록을 조회한다")
	void getUsers_success() throws Exception {
		// given
		AdminUserSummaryResponse summary = new AdminUserSummaryResponse(
			1L, "user@test.com", "홍길동", "닉네임", Gender.FEMALE, "https://img", 3L, 1L
		);
		PagingResponse<AdminUserSummaryResponse> response =
			new PagingResponse<>(List.of(summary), 0, 20, 1, 1, false, false);

		given(adminMemberService.getUsers(eq(null), any(Pageable.class))).willReturn(response);

		// when & then
		mockMvc.perform(get("/api/admin/users")
				.header("X-Member-Id", ADMIN_ID)
				.header("X-Member-Role", "ROLE_ADMIN"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value(SUCCESS_CODE))
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(1))
			.andExpect(jsonPath("$.data.content[0].email").value("user@test.com"))
			.andExpect(jsonPath("$.data.content[0].matchingTicketCount").value(3))
			.andExpect(jsonPath("$.data.content[0].optionTicketCount").value(1))
			.andExpect(jsonPath("$.data.totalElements").value(1));

		then(adminMemberService).should().getUsers(eq(null), any(Pageable.class));
	}

	@Test
	@DisplayName("GET /api/admin/users?keyword= - 키워드를 서비스로 그대로 전달한다")
	void getUsers_withKeyword() throws Exception {
		// given
		PagingResponse<AdminUserSummaryResponse> response =
			new PagingResponse<>(List.of(), 0, 20, 0, 0, false, false);

		given(adminMemberService.getUsers(eq("nickname"), any(Pageable.class))).willReturn(response);

		// when & then
		mockMvc.perform(get("/api/admin/users")
				.param("keyword", "nickname")
				.header("X-Member-Id", ADMIN_ID)
				.header("X-Member-Role", "ROLE_ADMIN"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value(SUCCESS_CODE))
			.andExpect(jsonPath("$.data.content.length()").value(0));

		then(adminMemberService).should().getUsers(eq("nickname"), any(Pageable.class));
	}

	@Test
	@DisplayName("GET /api/admin/users - 인증 헤더가 없으면 예외가 발생한다")
	void getUsers_missingMemberIdHeader() throws Exception {
		mockMvc.perform(get("/api/admin/users"))
			.andExpect(status().isInternalServerError());

		then(adminMemberService).shouldHaveNoInteractions();
	}
}
