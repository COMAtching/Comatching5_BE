package com.comatching.chat.global.exception;

import org.springframework.http.HttpStatus;

import com.comatching.common.exception.code.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ChatErrorCode implements ErrorCode {

	CHAT_ERROR("CHAT-000", HttpStatus.INTERNAL_SERVER_ERROR, "채팅 서버 에러."),
	NOT_EXIST_CHATROOM("CHAT-001", HttpStatus.NOT_FOUND, "존재하지 않는 채팅방입니다.")
	;

	private final String code;
	private final HttpStatus httpStatus;
	private final String message;
}
