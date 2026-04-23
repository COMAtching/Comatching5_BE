package com.comatching.item.global.exception;

import org.springframework.http.HttpStatus;

import com.comatching.common.exception.code.ErrorCode;

import lombok.Getter;

@Getter
public enum PaymentErrorCode implements ErrorCode {

	REQUEST_NOT_FOUND("PAY-001", HttpStatus.BAD_REQUEST, "존재하지 않는 결제건"),
	ALREADY_PROCESSED("PAY-002", HttpStatus.BAD_REQUEST, "이미 처리된 결제건"),
	PENDING_REQUEST_ALREADY_EXISTS("PAY-003", HttpStatus.BAD_REQUEST, "이미 대기 중인 충전 요청이 있습니다."),
	REAL_NAME_REQUIRED("PAY-004", HttpStatus.BAD_REQUEST, "실명을 입력해주세요."),
	NICKNAME_REQUIRED("PAY-005", HttpStatus.BAD_REQUEST, "닉네임 정보가 없어 결제를 요청할 수 없습니다."),
	REQUEST_EXPIRED("PAY-006", HttpStatus.BAD_REQUEST, "결제 요청이 만료되었습니다."),
	INVALID_ORDER_QUANTITY("PAY-007", HttpStatus.BAD_REQUEST, "요청 수량이 올바르지 않습니다."),
	INVALID_REQUESTED_PRICE("PAY-008", HttpStatus.BAD_REQUEST, "요청 금액이 서버 계산 금액과 일치하지 않습니다."),
	ITEM_NAME_REQUIRED("PAY-009", HttpStatus.BAD_REQUEST, "상품명을 입력해주세요."),
	USERNAME_REQUIRED("PAY-010", HttpStatus.BAD_REQUEST, "사용자명을 입력해주세요."),
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
