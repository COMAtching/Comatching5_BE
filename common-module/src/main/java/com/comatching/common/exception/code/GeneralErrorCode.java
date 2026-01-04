package com.comatching.common.exception.code;

import org.springframework.http.HttpStatus;
import lombok.Getter;

@Getter
public enum GeneralErrorCode implements ErrorCode {

	// 400 Bad Request
	BAD_REQUEST("GEN-001", HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
	INVALID_INPUT_VALUE("GEN-002", HttpStatus.BAD_REQUEST, "올바르지 않은 입력값입니다."),
	VALIDATION_FAILED("GEN-003", HttpStatus.BAD_REQUEST, "유효성 검사에 실패했습니다."),
	MISSING_REQUEST_PARAMETER("GEN-004", HttpStatus.BAD_REQUEST, "필수 요청 파라미터가 누락되었습니다."),
	TYPE_MISMATCH("GEN-005", HttpStatus.BAD_REQUEST, "파라미터 타입이 일치하지 않습니다."),
	JSON_PARSE_ERROR("GEN-006", HttpStatus.BAD_REQUEST, "JSON 파싱에 실패했습니다."),
	FILE_EXPIRED("GEN-103", HttpStatus.BAD_REQUEST, "파일의 유효기간이 만료되었습니다."),

	// 404 Not Found
	NOT_FOUND("GEN-007", HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),

	// 405 Method Not Allowed
	METHOD_NOT_ALLOWED("GEN-008", HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),

	// 415 Unsupported Media Type
	UNSUPPORTED_MEDIA_TYPE("GEN-009", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 미디어 타입입니다."),

	// 401 Unauthorized / 403 Forbidden
	UNAUTHORIZED("GEN-010", HttpStatus.UNAUTHORIZED, "인증되지 않은 사용자입니다."),
	FORBIDDEN("GEN-011", HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

	// 500 Internal Server Error
	INTERNAL_SERVER_ERROR("GEN-099", HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
	DATABASE_ERROR("GEN-100", HttpStatus.INTERNAL_SERVER_ERROR, "데이터베이스 오류가 발생했습니다."),
	IO_ERROR("GEN-101", HttpStatus.INTERNAL_SERVER_ERROR, "입출력 오류가 발생했습니다."),
	REDIS_CONNECTION_ERROR("GEN-102", HttpStatus.INTERNAL_SERVER_ERROR, "레디스 연결에 실패했습니다.");

	private final String code;
	private final HttpStatus httpStatus;
	private final String message;

	GeneralErrorCode(String code, HttpStatus httpStatus, String message) {
		this.code = code;
		this.httpStatus = httpStatus;
		this.message = message;
	}
}