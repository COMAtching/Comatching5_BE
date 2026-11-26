package com.comatching.user.infra.controller;

import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.comatching.common.dto.response.ApiResponse;
import com.comatching.user.domain.mail.service.VerificationMailLimitException;
import com.comatching.user.global.exception.UserErrorCode;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class VerificationMailAdvice {
	@ExceptionHandler(VerificationMailLimitException.class)
	public ResponseEntity<ApiResponse<Map<String, Long>>> limited(VerificationMailLimitException exception) {
		long retry = exception.getRetryAfterSeconds();
		var error = UserErrorCode.VERIFICATION_MAIL_LIMITED;
		return ResponseEntity.status(error.getHttpStatus()).header(HttpHeaders.RETRY_AFTER, Long.toString(retry))
			.body(ApiResponse.errorResponse(error, Map.of("retryAfterSeconds", retry)));
	}
}
