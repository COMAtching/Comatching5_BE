package com.comatching.notification;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import com.comatching.common.config.kafka.KafkaDltRedriveController;
import com.comatching.common.filter.InternalApiAuthenticationFilter;

/**
 * DLT 재적재 컨트롤러와 내부 인증 필터가 반드시 함께 등록되는지 검사한다.
 *
 * 이 서비스는 common 을 컴포넌트 스캔하지 않고 @Import 로 골라 쓰기 때문에,
 * 컨트롤러만 넣고 필터를 빠뜨리면 /api/internal/** 이 무인증으로 열린다
 * (SecurityConfig 는 해당 경로를 permitAll 로 두고 검사를 필터에 위임한다).
 * 실수로 한쪽만 지우는 회귀를 컴파일 수준이 아니라 테스트로 잡는다.
 */
class NotificationApplicationImportTest {

	@Test
	void dltRedriveControllerMustBeImportedWithInternalAuthFilter() {
		Import imports = NotificationApplication.class.getAnnotation(Import.class);

		assertThat(imports).isNotNull();
		assertThat(imports.value())
			.contains(KafkaDltRedriveController.class, InternalApiAuthenticationFilter.class);
	}
}
