package com.comatching.chat.global.exception;

import org.springframework.http.HttpStatus;

import com.comatching.common.exception.code.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ChatErrorCode implements ErrorCode {

	NOT_EXIST_CHATROOM("CHAT-001", HttpStatus.NOT_FOUND, "존재하지 않는 채팅방입니다."),
	;

	private final String code;
	private final HttpStatus httpStatus;
	private final String message;
}
