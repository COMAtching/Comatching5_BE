package com.comatching.chat.global.exception;

import org.springframework.http.HttpStatus;

import com.comatching.common.exception.code.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ChatErrorCode implements ErrorCode {

	CHAT_ERROR("CHAT-000", HttpStatus.INTERNAL_SERVER_ERROR, "채팅 서버 에러."),
	NOT_EXIST_CHATROOM("CHAT-001", HttpStatus.NOT_FOUND, "존재하지 않는 채팅방입니다."),
	CANNOT_BLOCK_SELF("CHAT-100", HttpStatus.BAD_REQUEST, "자기 자신을 차단할 수 없습니다."),
	ALREADY_BLOCKED("CHAT-101", HttpStatus.CONFLICT, "이미 차단한 사용자입니다."),
	NOT_BLOCKED("CHAT-102", HttpStatus.NOT_FOUND, "차단하지 않은 사용자입니다.")
	;

	private final String code;
	private final HttpStatus httpStatus;
	private final String message;
}
