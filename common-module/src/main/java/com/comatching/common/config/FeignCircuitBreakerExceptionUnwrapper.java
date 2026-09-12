package com.comatching.common.config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;

/**
 * 서킷브레이커가 씌운 래퍼를 벗겨 Feign 원본 예외를 복원한다.
 *
 * spring.cloud.openfeign.circuitbreaker.enabled 를 켜면 폴백 없는 Feign 호출의
 * 모든 예외가 NoFallbackAvailableException 으로 래핑되어 나온다. 그대로 두면
 * 기존 호출부의 catch (FeignException) 분기(예: 404 를 비즈니스 에러로 변환)가
 * 전부 조용히 무력화된다 — 컴파일 에러도 없이 의미가 바뀌는 종류의 회귀다.
 *
 * 그래서 Feign 클라이언트 빈을 한 겹 감싸 래퍼의 cause(FeignException,
 * CallNotPermittedException 등)를 그대로 다시 던진다. 호출부 입장에서는
 * 서킷브레이커 도입 전과 같은 예외 계약이 유지되고, 브레이커 open 만
 * CallNotPermittedException 이라는 새 예외로 추가된다.
 */
@Component
public class FeignCircuitBreakerExceptionUnwrapper implements BeanPostProcessor {

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (!isFeignClient(bean)) {
			return bean;
		}

		return Proxy.newProxyInstance(
			bean.getClass().getClassLoader(),
			bean.getClass().getInterfaces(),
			(proxy, method, args) -> {
				try {
					return method.invoke(bean, args);
				} catch (InvocationTargetException e) {
					Throwable thrown = e.getTargetException();
					if (thrown instanceof NoFallbackAvailableException && thrown.getCause() != null) {
						throw thrown.getCause();
					}
					throw thrown;
				}
			}
		);
	}

	private boolean isFeignClient(Object bean) {
		for (Class<?> itf : bean.getClass().getInterfaces()) {
			if (itf.isAnnotationPresent(FeignClient.class)) {
				return true;
			}
		}
		return false;
	}
}
