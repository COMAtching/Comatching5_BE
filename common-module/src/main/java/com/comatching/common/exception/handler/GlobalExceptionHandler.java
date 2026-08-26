package com.comatching.common.exception.handler;

import java.util.HashMap;
import java.util.Map;

import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
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
		if (e.getErrorData() != null) {
			log.warn(
				"[Business Exception] Code: {}, Message: {}, Data: {}",
				e.getErrorCode().getCode(),
				e.getMessage(),
				e.getErrorData()
			);
			return ResponseEntity
				.status(e.getErrorCode().getHttpStatus())
				.body(ApiResponse.errorResponse(e.getErrorCode(), e.getErrorData()));
		}

		log.warn("[Business Exception] Code: {}, Message: {}", e.getErrorCode().getCode(), e.getMessage());

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
	 * 필수 요청 파라미터 누락 (400)
	 */
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiResponse<Void>> handleMissingRequestParameterException(
		MissingServletRequestParameterException e) {
		log.warn("[Missing Request Parameter Exception] Parameter: {}", e.getParameterName());

		return ResponseEntity
			.status(GeneralErrorCode.MISSING_REQUEST_PARAMETER.getHttpStatus())
			.body(ApiResponse.errorResponse(GeneralErrorCode.MISSING_REQUEST_PARAMETER));
	}

	/**
	 * 존재하지 않는 API/정적 리소스 요청 (404)
	 */
	@ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
	public ResponseEntity<ApiResponse<Void>> handleNotFoundException(Exception e) {
		log.warn("[Not Found Exception] {}", e.getMessage());

		return ResponseEntity
			.status(GeneralErrorCode.NOT_FOUND.getHttpStatus())
			.body(ApiResponse.errorResponse(GeneralErrorCode.NOT_FOUND));
	}

	/**
	 * 서킷브레이커 open (503)
	 *
	 * 피호출 서비스가 장애 판정을 받아 호출이 차단된 상태. 서버 오류(500)가
	 * 아니라 일시적 불가(503)로 내려야 클라이언트·게이트웨이가 재시도 판단을
	 * 할 수 있다.
	 */
	@ExceptionHandler(CallNotPermittedException.class)
	public ResponseEntity<ApiResponse<Void>> handleCallNotPermittedException(CallNotPermittedException e) {
		log.error("[Circuit Breaker Open] Breaker: {}", e.getCausingCircuitBreakerName());

		return ResponseEntity
			.status(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE.getHttpStatus())
			.body(ApiResponse.errorResponse(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE));
	}

	/**
	 * Feign 연결 실패·타임아웃 (503)
	 *
	 * FeignException 의 하위 타입이지만 더 구체적이라 이 핸들러가 먼저 잡는다.
	 * HTTP 응답을 받은 게 아니므로 아래 핸들러처럼 응답 본문을 전달할 수 없고,
	 * 피호출 서비스의 일시적 불가(503)로 내린다.
	 */
	@ExceptionHandler(RetryableException.class)
	public ResponseEntity<ApiResponse<Void>> handleFeignRetryableException(RetryableException e) {
		log.error("[Feign Timeout] {}", e.getMessage());

		return ResponseEntity
			.status(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE.getHttpStatus())
			.body(ApiResponse.errorResponse(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE));
	}

	/**
	 * 서킷브레이커 래퍼 언랩 (방어선)
	 *
	 * 정상 경로에서는 FeignCircuitBreakerExceptionUnwrapper 가 프록시 단계에서
	 * 원본 예외로 복원하므로 여기까지 오지 않는다. 그 층을 우회한 호출이
	 * 생기더라도 원인에 맞는 응답이 나가도록 한 번 더 푼다.
	 */
	@ExceptionHandler(NoFallbackAvailableException.class)
	public ResponseEntity<?> handleNoFallbackAvailableException(NoFallbackAvailableException e) {
		Throwable cause = e.getCause();

		if (cause instanceof CallNotPermittedException callNotPermitted) {
			return handleCallNotPermittedException(callNotPermitted);
		}
		if (cause instanceof RetryableException retryable) {
			return handleFeignRetryableException(retryable);
		}
		if (cause instanceof FeignException feignException) {
			return handleFeignException(feignException);
		}
		return handleException(e);
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
