package com.comatching.item.global.exception;

import org.springframework.http.HttpStatus;

import com.comatching.common.exception.code.ErrorCode;

import lombok.Getter;

@Getter
public enum PaymentErrorCode implements ErrorCode {

	REQUEST_NOT_FOUND("PAY-001", HttpStatus.BAD_REQUEST, "존재하지 않는 결제건"),
	ALREADY_PROCESSED("PAY-002", HttpStatus.BAD_REQUEST, "이미 처리된 결제건"),
	;

	private final String code;
	private final HttpStatus httpStatus;
	private final String message;

	PaymentErrorCode(String code, HttpStatus httpStatus, String message) {
		this.code = code;
		this.httpStatus = httpStatus;
		this.message = message;
	}
}