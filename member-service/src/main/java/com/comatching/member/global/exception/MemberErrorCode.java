package com.comatching.member.global.exception;

import org.springframework.http.HttpStatus;

import com.comatching.common.exception.code.ErrorCode;

import lombok.Getter;

@Getter
public enum MemberErrorCode implements ErrorCode {

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

	MemberErrorCode(String code, HttpStatus httpStatus, String message) {
		this.code = code;
		this.httpStatus = httpStatus;
		this.message = message;
	}
}
