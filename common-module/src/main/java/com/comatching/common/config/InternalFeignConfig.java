package com.comatching.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import feign.RequestInterceptor;

@Configuration
public class InternalFeignConfig {

	private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

	@Bean
	public RequestInterceptor internalServiceTokenRequestInterceptor(
		@Value("${internal.service-token:}") String internalServiceToken
	) {
		return template -> {
			if (StringUtils.hasText(internalServiceToken)) {
				template.header(INTERNAL_TOKEN_HEADER, internalServiceToken);
			}
		};
	}
}
