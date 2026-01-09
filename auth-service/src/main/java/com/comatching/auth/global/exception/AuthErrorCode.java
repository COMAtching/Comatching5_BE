package com.comatching.auth.global.exception;

import org.springframework.http.HttpStatus;

import com.comatching.common.exception.code.ErrorCode;

import lombok.Getter;

@Getter
public enum AuthErrorCode implements ErrorCode {

	SEND_EMAIL_FAILED("AUTH-001", HttpStatus.INTERNAL_SERVER_ERROR, "이메일 전송에 실패했습니다."),
	EMAIL_NOT_AUTHENTICATED("AUTH-002", HttpStatus.BAD_REQUEST, "인증되지 않은 이메일입니다."),
	INVALID_AUTH_CODE("AUTH-003", HttpStatus.BAD_REQUEST, "인증번호가 올바르지 않습니다."),
	ACCOUNT_LOCKED("AUTH-004", HttpStatus.FORBIDDEN, "계정이 정지되었습니다. 관리자에게 문의하세요."),
	ACCOUNT_DISABLED("AUTH-005", HttpStatus.FORBIDDEN, "계정이 비활성화 되었습니다. (휴면/탈퇴/대기)"),
	ACCOUNT_EXPIRED("AUTH-006", HttpStatus.FORBIDDEN, "계정 유효기간이 만료되었습니다."),
	CREDENTIALS_EXPIRED("AUTH-007", HttpStatus.FORBIDDEN, "비밀번호 유효기간이 만료되었습니다."),
	LOGIN_FAILED("AUTH-008", HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 일치하지 않습니다."),
	PASSWORD_NOT_MATCH("AUTH-009", HttpStatus.UNAUTHORIZED, "비밀번호가 일치하지 않습니다."),
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
