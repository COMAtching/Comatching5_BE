package com.comatching.chat.infra.controller;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.comatching.chat.domain.dto.BlockedUserResponse;
import com.comatching.chat.domain.service.block.BlockService;
import com.comatching.chat.global.exception.ChatErrorCode;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.handler.GlobalExceptionHandler;
import com.comatching.common.resolver.MemberInfoArgumentResolver;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class BlockControllerTest {

	private MockMvc mockMvc;

	private ObjectMapper objectMapper;

	@Mock
	private BlockService blockService;

	@InjectMocks
	private BlockController blockController;

	private static final Long MEMBER_ID = 1L;
	private static final Long TARGET_USER_ID = 2L;
	private static final String SUCCESS_CODE = "GEN-000";

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		mockMvc = MockMvcBuilders.standaloneSetup(blockController)
			.setCustomArgumentResolvers(new MemberInfoArgumentResolver())
			.setControllerAdvice(new GlobalExceptionHandler(objectMapper))
			.build();
	}

	@Nested
	@DisplayName("POST /api/chat/blocks - 사용자 차단")
	class BlockUserTest {

		@Test
		@DisplayName("정상적으로 사용자를 차단한다")
		void blockUser_success() throws Exception {
			// given
			String requestBody = """
				{
					"targetUserId": 2
				}
				""";
			willDoNothing().given(blockService).blockUser(MEMBER_ID, TARGET_USER_ID);

			// when & then
			mockMvc.perform(post("/api/chat/blocks")
					.header("X-Member-Id", MEMBER_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestBody))
				.andDo(print())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value(SUCCESS_CODE));
		}

		@Test
		@DisplayName("자기 자신을 차단하면 400 에러를 반환한다")
		void blockUser_cannotBlockSelf() throws Exception {
			// given
			String requestBody = """
				{
					"targetUserId": 1
				}
				""";
			willThrow(new BusinessException(ChatErrorCode.CANNOT_BLOCK_SELF))
				.given(blockService).blockUser(MEMBER_ID, MEMBER_ID);

			// when & then
			mockMvc.perform(post("/api/chat/blocks")
					.header("X-Member-Id", MEMBER_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestBody))
				.andDo(print())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ChatErrorCode.CANNOT_BLOCK_SELF.getCode()));
		}

		@Test
		@DisplayName("이미 차단한 사용자를 차단하면 409 에러를 반환한다")
		void blockUser_alreadyBlocked() throws Exception {
			// given
			String requestBody = """
				{
					"targetUserId": 2
				}
				""";
			willThrow(new BusinessException(ChatErrorCode.ALREADY_BLOCKED))
				.given(blockService).blockUser(MEMBER_ID, TARGET_USER_ID);

			// when & then
			mockMvc.perform(post("/api/chat/blocks")
					.header("X-Member-Id", MEMBER_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestBody))
				.andDo(print())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(ChatErrorCode.ALREADY_BLOCKED.getCode()));
		}
	}

	@Nested
	@DisplayName("DELETE /api/chat/blocks/{targetUserId} - 차단 해제")
	class UnblockUserTest {

		@Test
		@DisplayName("정상적으로 차단을 해제한다")
		void unblockUser_success() throws Exception {
			// given
			willDoNothing().given(blockService).unblockUser(MEMBER_ID, TARGET_USER_ID);

			// when & then
			mockMvc.perform(delete("/api/chat/blocks/{targetUserId}", TARGET_USER_ID)
					.header("X-Member-Id", MEMBER_ID))
				.andDo(print())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value(SUCCESS_CODE));
		}

		@Test
		@DisplayName("차단하지 않은 사용자를 해제하면 404 에러를 반환한다")
		void unblockUser_notBlocked() throws Exception {
			// given
			willThrow(new BusinessException(ChatErrorCode.NOT_BLOCKED))
				.given(blockService).unblockUser(MEMBER_ID, TARGET_USER_ID);

			// when & then
			mockMvc.perform(delete("/api/chat/blocks/{targetUserId}", TARGET_USER_ID)
					.header("X-Member-Id", MEMBER_ID))
				.andDo(print())
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value(ChatErrorCode.NOT_BLOCKED.getCode()));
		}
	}

	@Nested
	@DisplayName("GET /api/chat/blocks - 차단 목록 조회")
	class GetBlockedUsersTest {

		@Test
		@DisplayName("차단한 사용자 목록을 반환한다")
		void getBlockedUsers_success() throws Exception {
			// given
			List<BlockedUserResponse> blockedUsers = List.of(
				new BlockedUserResponse(2L, LocalDateTime.of(2024, 1, 1, 12, 0)),
				new BlockedUserResponse(3L, LocalDateTime.of(2024, 1, 2, 12, 0))
			);
			given(blockService.getBlockedUsers(MEMBER_ID)).willReturn(blockedUsers);

			// when & then
			mockMvc.perform(get("/api/chat/blocks")
					.header("X-Member-Id", MEMBER_ID))
				.andDo(print())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value(SUCCESS_CODE))
				.andExpect(jsonPath("$.data").isArray())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[0].userId").value(2))
				.andExpect(jsonPath("$.data[1].userId").value(3));
		}

		@Test
		@DisplayName("차단한 사용자가 없으면 빈 목록을 반환한다")
		void getBlockedUsers_emptyList() throws Exception {
			// given
			given(blockService.getBlockedUsers(MEMBER_ID)).willReturn(List.of());

			// when & then
			mockMvc.perform(get("/api/chat/blocks")
					.header("X-Member-Id", MEMBER_ID))
				.andDo(print())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value(SUCCESS_CODE))
				.andExpect(jsonPath("$.data").isArray())
				.andExpect(jsonPath("$.data.length()").value(0));
		}
	}

	@Nested
	@DisplayName("GET /api/chat/blocks/{targetUserId}/status - 차단 상태 확인")
	class GetBlockStatusTest {

		@Test
		@DisplayName("차단된 사용자면 isBlocked가 true를 반환한다")
		void getBlockStatus_blocked() throws Exception {
			// given
			given(blockService.isBlocked(MEMBER_ID, TARGET_USER_ID)).willReturn(true);

			// when & then
			mockMvc.perform(get("/api/chat/blocks/{targetUserId}/status", TARGET_USER_ID)
					.header("X-Member-Id", MEMBER_ID))
				.andDo(print())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value(SUCCESS_CODE))
				.andExpect(jsonPath("$.data.isBlocked").value(true));
		}

		@Test
		@DisplayName("차단되지 않은 사용자면 isBlocked가 false를 반환한다")
		void getBlockStatus_notBlocked() throws Exception {
			// given
			given(blockService.isBlocked(MEMBER_ID, TARGET_USER_ID)).willReturn(false);

			// when & then
			mockMvc.perform(get("/api/chat/blocks/{targetUserId}/status", TARGET_USER_ID)
					.header("X-Member-Id", MEMBER_ID))
				.andDo(print())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value(SUCCESS_CODE))
				.andExpect(jsonPath("$.data.isBlocked").value(false));
		}
	}
}
