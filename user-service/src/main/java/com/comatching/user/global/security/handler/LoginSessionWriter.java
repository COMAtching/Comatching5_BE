package com.comatching.user.global.security.handler;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.WebUtils;
import com.comatching.common.auth.PushInstallations;

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
	public boolean establish(UserPrincipal principal, HttpServletRequest request, HttpServletResponse response) throws IOException {
		try {
			var installation = cookies.installationId(cookieValue(request, PushInstallations.COOKIE));
			if (installation == null && cookies.canIssueInstallation()) installation = java.util.UUID.randomUUID();
			cookies.writeTokens(response, sessions.login(principal.getId(), principal.getAuthVersion(), installation,
				cookieValue(request, "accessToken"), cookieValue(request, "refreshToken")));
			if (installation != null) response.addHeader("Set-Cookie", cookies.installation(installation).toString());
			return true;
		} catch (BusinessException e) {
			writeFailure(response, e);
			return false;
		}
	}

	public void writeFailure(HttpServletResponse response, BusinessException error) throws IOException {
		response.setStatus(error.getErrorCode().getHttpStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		mapper.writeValue(response.getWriter(), ApiResponse.errorResponse(error.getErrorCode(), error.getErrorData()));
	}

	private static String cookieValue(HttpServletRequest request, String name) {
		var cookie = WebUtils.getCookie(request, name);
		return cookie == null ? null : cookie.getValue();
	}
}
