package com.comatching.chat.global.exception;

import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.ControllerAdvice;

import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.exception.BusinessException;

@ControllerAdvice
public class ChatErrorHandler {

	@MessageExceptionHandler(ChatException.class)
	@SendToUser("/queue/errors")
	public String handleException(ChatException e) {
		return "ERROR: " + e.getMessage();
	}

	// 채팅 코드가 실제로 던지는 예외는 BusinessException 이다. 위의 ChatException
	// 핸들러는 던지는 곳이 없어서 한 번도 발화하지 않았고, 그동안 방이 없거나
	// 권한이 없어 실패한 전송이 클라이언트에 아무 신호도 남기지 못했다.
	@MessageExceptionHandler(BusinessException.class)
	@SendToUser("/queue/errors")
	public ApiResponse<Void> handleBusinessException(BusinessException e) {
		return ApiResponse.errorResponse(e.getErrorCode());
	}
}
