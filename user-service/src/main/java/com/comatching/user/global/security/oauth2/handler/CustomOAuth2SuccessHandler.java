package com.comatching.user.global.security.oauth2.handler;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.comatching.user.global.security.oauth2.user.CustomOAuth2User;
import com.comatching.user.global.security.handler.LoginSessionWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CustomOAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

	private final LoginSessionWriter sessions;

	@Value("${client.url}")
	private String clientUrl;

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
		Authentication authentication) throws IOException, ServletException {

		// UserPrincipal(CustomOAuth2User) 추출
		CustomOAuth2User userPrincipal = (CustomOAuth2User)authentication.getPrincipal();

		String role = userPrincipal.getRole();
		if (!sessions.establish(userPrincipal, response)) return;

		if (role.equals("ROLE_GUEST")) {
			getRedirectStrategy().sendRedirect(request, response, clientUrl + "/onboarding");
		} else {
			getRedirectStrategy().sendRedirect(request, response, clientUrl + "/main");
		}
	}
}
