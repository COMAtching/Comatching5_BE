package com.comatching.common.dto.response;

import org.springframework.http.HttpStatus;

import com.comatching.common.exception.code.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

	private static final String SUCCESS_CODE = "GEN-000";
	private static final String SUCCESS_MESSAGE = "요청이 성공적으로 처리되었습니다.";

	private String code;
	private int status;
	private String message;
	private T data;

	public static <T> ApiResponse<T> ok() {
		return ApiResponse.<T>builder()
			.code(SUCCESS_CODE)
			.status(HttpStatus.OK.value())
			.message(SUCCESS_MESSAGE)
			.build();
	}

	public static <T> ApiResponse<T> ok(T data) {
		return ApiResponse.<T>builder()
			.code(SUCCESS_CODE)
			.status(HttpStatus.OK.value())
			.message(SUCCESS_MESSAGE)
			.data(data)
			.build();
	}

	public static <T> ApiResponse<T> errorResponse(ErrorCode errorCode) {
		return ApiResponse.<T>builder()
			.code(errorCode.getCode())
			.status(errorCode.getHttpStatus().value())
			.message(errorCode.getMessage())
			.build();
	}

	public static <T> ApiResponse<T> errorResponse(ErrorCode errorCode, T data) {
		return ApiResponse.<T>builder()
			.code(errorCode.getCode())
			.status(errorCode.getHttpStatus().value())
			.message(errorCode.getMessage())
			.data(data)
			.build();
	}
}
