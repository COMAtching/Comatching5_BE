package com.comatching.chat.global.exception;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

class ChatErrorHandlerTest {

	private final ChatErrorHandler handler = new ChatErrorHandler();

	@Test
	@DisplayName("존재하지 않는 채팅방 예외를 에러 코드가 담긴 응답으로 바꿔 내려준다")
	void handleBusinessException_carriesChatErrorCode() {
		ApiResponse<Void> response =
			handler.handleBusinessException(new BusinessException(ChatErrorCode.NOT_EXIST_CHATROOM));

		assertThat(response.getCode()).isEqualTo(ChatErrorCode.NOT_EXIST_CHATROOM.getCode());
		assertThat(response.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(response.getMessage()).isEqualTo(ChatErrorCode.NOT_EXIST_CHATROOM.getMessage());
	}

	@Test
	@DisplayName("남의 방에 보낸 경우도 같은 형태로 내려준다")
	void handleBusinessException_carriesGeneralErrorCode() {
		ApiResponse<Void> response =
			handler.handleBusinessException(new BusinessException(GeneralErrorCode.FORBIDDEN));

		assertThat(response.getCode()).isEqualTo(GeneralErrorCode.FORBIDDEN.getCode());
		assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
	}
}
