package com.comatching.user.global.security.handler;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.user.domain.auth.service.LoginSessionService;
import com.comatching.user.global.security.UserPrincipal;
import com.comatching.user.global.security.cookie.AuthCookieFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class LoginSessionWriter {
	private final LoginSessionService sessions;
	private final AuthCookieFactory cookies;
	private final ObjectMapper mapper;

	// Authentication success handlers run outside MVC's exception advice.
	public boolean establish(UserPrincipal principal, HttpServletResponse response) throws IOException {
		try {
			cookies.writeTokens(response, sessions.login(principal.getId(), principal.getAuthVersion()));
			return true;
		} catch (BusinessException e) {
			response.setStatus(e.getErrorCode().getHttpStatus().value());
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding("UTF-8");
			mapper.writeValue(response.getWriter(), ApiResponse.errorResponse(e.getErrorCode()));
			return false;
		}
	}
}
