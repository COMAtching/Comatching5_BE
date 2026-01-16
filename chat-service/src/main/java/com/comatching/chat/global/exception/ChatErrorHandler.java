package com.comatching.chat.global.exception;

import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.ControllerAdvice;

@ControllerAdvice
public class ChatErrorHandler {

	@MessageExceptionHandler(ChatException.class)
	@SendToUser("/queue/errors")
	public String handleException(ChatException e) {
		return "ERROR: " + e.getMessage();
	}
}
