package com.comatching.chat.global.exception;

import com.comatching.common.exception.code.ErrorCode;

import lombok.Getter;

@Getter
public class ChatException extends RuntimeException {

	private final ErrorCode errorCode;
	private final Object errorData;

	public ChatException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
		this.errorData = null;
	}

	public ChatException(ErrorCode errorCode, String customMessage) {
		super(customMessage);
		this.errorCode = errorCode;
		this.errorData = null;
	}

	public ChatException(ErrorCode errorCode, Object errorData, String customMessage) {
		super(customMessage);
		this.errorCode = errorCode;
		this.errorData = errorData;
	}
}
