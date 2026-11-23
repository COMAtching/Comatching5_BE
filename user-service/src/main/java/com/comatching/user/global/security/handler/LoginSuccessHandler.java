package com.comatching.user.global.security.handler;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import com.comatching.user.global.security.UserPrincipal;
import com.comatching.common.dto.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

	private final LoginSessionWriter sessions;
	private final ObjectMapper objectMapper;

	@Value("${client.url}")
	private String clientUrl;

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
		Authentication authentication) throws IOException, ServletException {

		UserPrincipal principal = (UserPrincipal)authentication.getPrincipal();
		String role = principal.getRole();

		if (!sessions.establish(principal, response)) return;

		if (role.equals("ROLE_ADMIN")) {
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding("UTF-8");
			ApiResponse<Void> apiResponse = ApiResponse.ok();
			objectMapper.writeValue(response.getWriter(), apiResponse);
			return;
		}

		if (role.equals("ROLE_GUEST")) {
			response.sendRedirect(clientUrl + "/onboarding");
		} else {
			response.sendRedirect(clientUrl + "/main");
		}
	}
}
