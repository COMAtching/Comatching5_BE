package com.comatching.user.global.security.handler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import com.comatching.user.global.exception.UserErrorCode;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.exception.code.ErrorCode;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LoginFailureHandler implements AuthenticationFailureHandler {

	private final ObjectMapper objectMapper;

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
		AuthenticationException exception) throws IOException, ServletException {

		ErrorCode errorCode = GeneralErrorCode.UNAUTHORIZED;

		if (exception instanceof LockedException) {
			// UserPrincipal.isAccountNonLocked() == false (BANNED, SUSPENDED)
			errorCode = UserErrorCode.ACCOUNT_LOCKED;
		} else if (exception instanceof DisabledException) {
			// UserPrincipal.isEnabled() == false (WITHDRAWN, DORMANT)
			errorCode = UserErrorCode.ACCOUNT_DISABLED;
		} else if (exception instanceof AccountExpiredException) {
			errorCode = UserErrorCode.ACCOUNT_EXPIRED;
		} else if (exception instanceof CredentialsExpiredException) {
			errorCode = UserErrorCode.CREDENTIALS_EXPIRED;
		} else if (exception instanceof BadCredentialsException) {
			errorCode = UserErrorCode.LOGIN_FAILED;
		}

		response.setStatus(errorCode.getHttpStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());

		ApiResponse<Void> apiResponse = ApiResponse.errorResponse(errorCode);

		objectMapper.writeValue(response.getWriter(), apiResponse);
	}
}
