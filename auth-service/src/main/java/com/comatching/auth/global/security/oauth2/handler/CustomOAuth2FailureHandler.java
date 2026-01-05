package com.comatching.auth.global.security.oauth2.handler;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class CustomOAuth2FailureHandler extends SimpleUrlAuthenticationFailureHandler {

	@Value("${client.url:http://localhost:3000}")
	private String clientUrl;

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
		AuthenticationException exception) throws IOException, ServletException {
		super.onAuthenticationFailure(request, response, exception);

		getRedirectStrategy().sendRedirect(request, response,clientUrl + "/oauth2/callback/failure");
	}
}
