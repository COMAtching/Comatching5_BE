package com.comatching.gateway.exception;

import com.comatching.common.exception.code.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum GatewayErrorCode implements ErrorCode {

	// JWT / Auth 관련
	TOKEN_MISSING("GATEWAY-001", HttpStatus.UNAUTHORIZED, "토큰이 누락되었습니다."),
	TOKEN_INVALID("GATEWAY-002", HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
	TOKEN_EXPIRED("GATEWAY-003", HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),

	// 기타 Gateway 오류
	SERVER_ERROR("GATEWAY-004", HttpStatus.INTERNAL_SERVER_ERROR, "게이트웨이 내부 오류입니다.");

	private final String code;
	private final HttpStatus httpStatus;
	private final String message;
}