package com.comatching.auth.global.security.oauth2.handler;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import com.comatching.auth.global.exception.AuthErrorCode;
import com.comatching.common.exception.code.ErrorCode;
import com.comatching.common.exception.code.GeneralErrorCode;

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

		String errorCode = GeneralErrorCode.UNAUTHORIZED.name();

		if (exception instanceof LockedException) {
			// UserPrincipal.isAccountNonLocked() == false (BANNED, SUSPENDED)
			errorCode = AuthErrorCode.ACCOUNT_LOCKED.name();
		} else if (exception instanceof DisabledException) {
			// UserPrincipal.isEnabled() == false (WITHDRAWN, DORMANT)
			errorCode = AuthErrorCode.ACCOUNT_DISABLED.name();
		} else if (exception instanceof AccountExpiredException) {
			errorCode = AuthErrorCode.ACCOUNT_EXPIRED.name();
		} else if (exception instanceof CredentialsExpiredException) {
			errorCode = AuthErrorCode.CREDENTIALS_EXPIRED.name();
		} else if (exception instanceof BadCredentialsException) {
			errorCode = AuthErrorCode.LOGIN_FAILED.name();
		}

		String targetUrl = clientUrl + "/oauth2/callback/failure?error=" + errorCode;
		getRedirectStrategy().sendRedirect(request, response, targetUrl);
	}
}
