package com.comatching.common.filter;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalApiAuthenticationFilter extends OncePerRequestFilter {

	private static final String INTERNAL_PATH_PREFIX = "/api/internal/";
	private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

	private final String internalServiceToken;
	private final ObjectMapper objectMapper;

	public InternalApiAuthenticationFilter(
		@Value("${internal.service-token:}") String internalServiceToken,
		ObjectMapper objectMapper
	) {
		this.internalServiceToken = internalServiceToken;
		this.objectMapper = objectMapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith(INTERNAL_PATH_PREFIX);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {

		String requestToken = request.getHeader(INTERNAL_TOKEN_HEADER);
		if (!StringUtils.hasText(internalServiceToken) || !internalServiceToken.equals(requestToken)) {
			writeUnauthorizedResponse(response);
			return;
		}

		filterChain.doFilter(request, response);
	}

	private void writeUnauthorizedResponse(HttpServletResponse response) throws IOException {
		response.setStatus(GeneralErrorCode.UNAUTHORIZED.getHttpStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getWriter(), ApiResponse.errorResponse(GeneralErrorCode.UNAUTHORIZED));
	}
}
