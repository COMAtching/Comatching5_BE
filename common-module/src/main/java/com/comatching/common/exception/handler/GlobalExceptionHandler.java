package com.comatching.common.exception.handler;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

	/**
	 * BusinessException 처리
	 */
	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiResponse<Object>> handleBusinessException(BusinessException e) {
		log.warn("[Business Exception] Code: {}, Message: {}", e.getErrorCode().getCode(), e.getMessage());

		if (e.getErrorData() != null) {
			return ResponseEntity
				.status(e.getErrorCode().getHttpStatus())
				.body(ApiResponse.errorResponse(e.getErrorCode(), e.getErrorData()));
		}

		return ResponseEntity
			.status(e.getErrorCode().getHttpStatus())
			.body(ApiResponse.errorResponse(e.getErrorCode()));
	}

	/**
	 * @Valid 유효성 검사 실패 (400)
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationException(MethodArgumentNotValidException e) {
		log.warn("[Validation Exception] {}", e.getMessage());

		BindingResult bindingResult = e.getBindingResult();
		Map<String, String> errors = new HashMap<>();

		// 실패한 필드와 메시지를 맵에 담음 (예: "email": "이메일 형식이 아닙니다")
		for (FieldError fieldError : bindingResult.getFieldErrors()) {
			errors.put(fieldError.getField(), fieldError.getDefaultMessage());
		}

		return ResponseEntity
			.status(GeneralErrorCode.VALIDATION_FAILED.getHttpStatus())
			.body(ApiResponse.errorResponse(GeneralErrorCode.VALIDATION_FAILED, errors));
	}

	/**
	 * JSON 파싱 실패 (400)
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleJsonException(HttpMessageNotReadableException e) {
		log.warn("[JSON Parse Exception] {}", e.getMessage());

		return ResponseEntity
			.status(GeneralErrorCode.JSON_PARSE_ERROR.getHttpStatus())
			.body(ApiResponse.errorResponse(GeneralErrorCode.JSON_PARSE_ERROR));
	}

	/**
	 * URL 파라미터 타입 불일치 (400)
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiResponse<Void>> handleTypeMismatchException(MethodArgumentTypeMismatchException e) {
		log.warn("[Type Mismatch Exception] Field: {}, Value: {}", e.getName(), e.getValue());

		return ResponseEntity
			.status(GeneralErrorCode.TYPE_MISMATCH.getHttpStatus())
			.body(ApiResponse.errorResponse(GeneralErrorCode.TYPE_MISMATCH));
	}

	/**
	 * [5] 나머지 모든 예외 (500)
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
		log.error("[Unhandled Exception] ", e);

		return ResponseEntity
			.status(GeneralErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
			.body(ApiResponse.errorResponse(GeneralErrorCode.INTERNAL_SERVER_ERROR));
	}
}
