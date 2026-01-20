package com.comatching.common.exception.handler;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

	private final ObjectMapper objectMapper;

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
	public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationException(
		MethodArgumentNotValidException e) {
		log.warn("[Validation Exception] {}", e.getMessage());

		BindingResult bindingResult = e.getBindingResult();
		Map<String, String> errors = new HashMap<>();

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
	 * 필수 쿠키 누락 (400)
	 */
	@ExceptionHandler(MissingRequestCookieException.class)
	public ResponseEntity<ApiResponse<Void>> handleMissingCookieException(MissingRequestCookieException e) {
		log.warn("[Missing Cookie Exception] Cookie: {}", e.getCookieName());

		return ResponseEntity
			.status(GeneralErrorCode.MISSING_REQUEST_PARAMETER.getHttpStatus())
			.body(ApiResponse.errorResponse(GeneralErrorCode.MISSING_REQUEST_PARAMETER));
	}

	/**
	 * Feign Client 예외 처리
	 */
	@ExceptionHandler(FeignException.class)
	public ResponseEntity<Object> handleFeignException(FeignException e) {
		String responseBody = e.contentUTF8();
		int status = e.status();

		if (responseBody != null && !responseBody.isBlank()) {
			try {
				Object jsonNode = objectMapper.readValue(responseBody, Object.class);
				return ResponseEntity.status(status).body(jsonNode);
			} catch (JsonProcessingException ex) {
				log.warn("[Feign] JSON Parsing Failed. Raw body: {}", responseBody);
			}
		}

		log.error("[Feign Error] Status: {}, Message: {}", status, e.getMessage());

		return ResponseEntity
			.status(GeneralErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
			.body(ApiResponse.errorResponse(GeneralErrorCode.INTERNAL_SERVER_ERROR));
	}

	/**
	 * 나머지 모든 예외 (500)
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
		log.error("[Unhandled Exception] ", e);

		return ResponseEntity
			.status(GeneralErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
			.body(ApiResponse.errorResponse(GeneralErrorCode.INTERNAL_SERVER_ERROR));
	}
}
