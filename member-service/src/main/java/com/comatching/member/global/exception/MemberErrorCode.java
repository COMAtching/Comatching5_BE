package com.comatching.member.global.exception;

import org.springframework.http.HttpStatus;

import com.comatching.common.exception.code.ErrorCode;

import lombok.Getter;

@Getter
public enum MemberErrorCode implements ErrorCode {

	USER_NOT_EXIST("MEM-001", HttpStatus.BAD_REQUEST, "존재하지 않는 유저입니다.");
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
