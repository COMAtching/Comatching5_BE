package com.comatching.matching.global.exception;

import org.springframework.http.HttpStatus;

import com.comatching.common.exception.code.ErrorCode;

import lombok.Getter;

@Getter
public enum MatchingErrorCode implements ErrorCode {
	NO_MATCHING_CANDIDATE("MATCH-001", HttpStatus.INTERNAL_SERVER_ERROR, "매칭 후보가 없습니다."),
	;

	private final String code;
	private final HttpStatus httpStatus;
	private final String message;

	MatchingErrorCode(String code, HttpStatus httpStatus, String message) {
		this.code = code;
		this.httpStatus = httpStatus;
		this.message = message;
	}
}
