package com.comatching.user.global.exception;

import org.springframework.http.HttpStatus;

import com.comatching.common.exception.code.ErrorCode;

import lombok.Getter;

@Getter
public enum UserErrorCode implements ErrorCode {

	// Auth 관련 에러 (AUTH-001 ~ AUTH-099)
	SEND_EMAIL_FAILED("AUTH-001", HttpStatus.INTERNAL_SERVER_ERROR, "이메일 전송에 실패했습니다."),
	EMAIL_NOT_AUTHENTICATED("AUTH-002", HttpStatus.BAD_REQUEST, "인증되지 않은 이메일입니다."),
	INVALID_AUTH_CODE("AUTH-003", HttpStatus.BAD_REQUEST, "인증번호가 올바르지 않습니다."),
	ACCOUNT_LOCKED("AUTH-004", HttpStatus.FORBIDDEN, "계정이 정지되었습니다."),
	ACCOUNT_DISABLED("AUTH-005", HttpStatus.FORBIDDEN, "계정이 비활성화 되었습니다. (휴면/탈퇴/대기)"),
	ACCOUNT_EXPIRED("AUTH-006", HttpStatus.FORBIDDEN, "계정 유효기간이 만료되었습니다."),
	CREDENTIALS_EXPIRED("AUTH-007", HttpStatus.FORBIDDEN, "비밀번호 유효기간이 만료되었습니다."),
	LOGIN_FAILED("AUTH-008", HttpStatus.UNAUTHORIZED, "계정이 존재하지 않거나 로그인 정보가 일치하지 않습니다."),
	PASSWORD_NOT_MATCH("AUTH-009", HttpStatus.UNAUTHORIZED, "비밀번호가 일치하지 않습니다."),

	// Member 관련 에러 (MEM-001 ~ MEM-099)
	USER_NOT_EXIST("MEM-001", HttpStatus.BAD_REQUEST, "존재하지 않는 유저입니다."),
	DUPLICATE_EMAIL("MEM-002", HttpStatus.BAD_REQUEST, "중복된 이메일입니다."),
	PROFILE_ALREADY_EXISTS("MEM-003", HttpStatus.BAD_REQUEST, "프로필이 이미 존재합니다."),
	PROFILE_NOT_EXISTS("MEM-004", HttpStatus.BAD_REQUEST, "프로필이 존재하지 않습니다."),
	INVALID_SOCIAL_INFO("MEM-005", HttpStatus.BAD_REQUEST, "소셜 정보는 타입과 ID가 함께 입력되어야 합니다."),
	INVALID_HOBBY_COUNT("MEM-006", HttpStatus.BAD_REQUEST, "취미는 최소 2개 이상 최대 5개 이하를 등록해야 합니다."),
	INTRO_LIMIT_EXCEEDED("MEM-007", HttpStatus.BAD_REQUEST, "소개 항목이 최대 갯수(3개)를 초과했습니다."),
	;

	private final String code;
	private final HttpStatus httpStatus;
	private final String message;

	UserErrorCode(String code, HttpStatus httpStatus, String message) {
		this.code = code;
		this.httpStatus = httpStatus;
		this.message = message;
	}
}
