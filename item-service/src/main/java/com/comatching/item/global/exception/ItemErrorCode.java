package com.comatching.item.global.exception;

import org.springframework.http.HttpStatus;

import com.comatching.common.exception.code.ErrorCode;

import lombok.Getter;

@Getter
public enum ItemErrorCode implements ErrorCode {

	NOT_ENOUGH_ITEM("ITEM-001", HttpStatus.BAD_REQUEST, "아이템이 부족합니다."),
	PRODUCT_NOT_FOUND("ITEM-002", HttpStatus.BAD_REQUEST, "상품을 찾을 수 없습니다."),
	PRODUCT_NOT_AVAILABLE("ITEM-003", HttpStatus.BAD_REQUEST, "유효하지 않은 상품"),
	;

	private final String code;
	private final HttpStatus httpStatus;
	private final String message;

	ItemErrorCode(String code, HttpStatus httpStatus, String message) {
		this.code = code;
		this.httpStatus = httpStatus;
		this.message = message;
	}
}
