package com.comatching.common.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.CircuitBreakerNameResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import feign.RequestInterceptor;
import feign.Retryer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

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

	/**
	 * Spring Cloud OpenFeign 기본은 NEVER_RETRY. 이 빈이 그 기본을 대체한다.
	 * 재시도 범위·근거는 InternalGetRetryer 참고.
	 */
	@Bean
	public Retryer feignRetryer() {
		return new InternalGetRetryer();
	}

	/**
	 * 브레이커 open/복구를 웹훅으로 알린다. 브레이커는 Feign 호출 시점에
	 * 레지스트리에 늦게 만들어지므로, 기존 항목 순회가 아니라 onEntryAdded
	 * 훅으로 앞으로 만들어질 브레이커 전부에 리스너를 건다.
	 */
	@Bean
	public CircuitBreakerAlertNotifier circuitBreakerAlertNotifier(
		@Value("${comatching.feign.circuit-breaker-alert-webhook-url:}") String webhookUrl,
		@Value("${spring.application.name:unknown}") String serviceName,
		CircuitBreakerRegistry circuitBreakerRegistry
	) {
		CircuitBreakerAlertNotifier notifier =
			new CircuitBreakerAlertNotifier(webhookUrl, serviceName, Duration.ofMinutes(5));

		circuitBreakerRegistry.getEventPublisher().onEntryAdded(event ->
			event.getAddedEntry().getEventPublisher().onStateTransition(notifier::onStateTransition)
		);
		return notifier;
	}
}
