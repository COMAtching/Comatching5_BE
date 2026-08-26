package com.comatching.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.CircuitBreakerNameResolver;
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

	/**
	 * 서킷브레이커를 Feign 클라이언트(name) 단위로 묶는다.
	 *
	 * 기본 리졸버는 메서드 시그니처까지 붙여 브레이커를 만드는데, 그러면
	 * 트래픽이 메서드별로 쪼개져 minimum-number-of-calls(feign-resilience.yml)에
	 * 도달하지 못한 브레이커는 영영 열리지 않는다. 장애는 엔드포인트가 아니라
	 * 프로세스 단위로 오므로(피호출 서비스의 GC 멈춤·커넥션 고갈은 그 서비스의
	 * 모든 엔드포인트를 함께 느리게 만든다) 서비스 단위 판단이 맞다.
	 */
	@Bean
	public CircuitBreakerNameResolver feignClientCircuitBreakerNameResolver() {
		return (feignClientName, target, method) -> feignClientName;
	}
}
