package com.comatching.user.global.security.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.util.StreamUtils;

import com.comatching.user.domain.auth.dto.LoginRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class CustomJsonUsernamePasswordAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

	private final ObjectMapper objectMapper;

	public CustomJsonUsernamePasswordAuthenticationFilter(ObjectMapper objectMapper) {
		super("/api/auth/login");
		this.objectMapper = objectMapper;
	}

	@Override
	public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) throws
		AuthenticationException,
		IOException,
		ServletException {

		// Content-Type 확인
		if (request.getContentType() == null || !request.getContentType().equals("application/json")) {
			throw new AuthenticationServiceException("Authentication method not supported: " + request.getMethod());
		}

		// JSON 요청 바디를 LoginRequest 객체로 변환
		String messageBody = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
		LoginRequest loginRequest = objectMapper.readValue(messageBody, LoginRequest.class);

		// 인증 토큰 생성 (아직 인증되지 않은 상태)
		UsernamePasswordAuthenticationToken authRequest =
			new UsernamePasswordAuthenticationToken(loginRequest.email(), loginRequest.password());

		// AuthenticationManager에게 검증 위임 -> CustomUserDetailsService가 호출됨
		return this.getAuthenticationManager().authenticate(authRequest);
	}
}
