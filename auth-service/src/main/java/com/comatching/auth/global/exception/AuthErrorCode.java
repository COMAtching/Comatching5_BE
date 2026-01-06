package com.comatching.auth.global.exception;

import org.springframework.http.HttpStatus;

import com.comatching.common.exception.code.ErrorCode;

import lombok.Getter;

@Getter
public enum AuthErrorCode implements ErrorCode {

	SEND_EMAIL_FAILED("AUTH-001", HttpStatus.INTERNAL_SERVER_ERROR, "이메일 전송에 실패했습니다."),
	EMAIL_NOT_AUTHENTICATED("AUTH-002", HttpStatus.BAD_REQUEST, "인증되지 않은 이메일입니다."),
	INVALID_AUTH_CODE("AUTH-002", HttpStatus.BAD_REQUEST, "인증번호가 올바르지 않습니다."),
	;

	private final String code;
	private final HttpStatus httpStatus;
	private final String message;

	AuthErrorCode(String code, HttpStatus httpStatus, String message) {
		this.code = code;
		this.httpStatus = httpStatus;
		this.message = message;
	}
}
