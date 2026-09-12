package com.comatching.common.config;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * feign-resilience.yml 이 실제로 FeignClientProperties 에 바인딩되는지 검증.
 *
 * 이 파일은 각 서비스가 spring.config.import 로 불러 쓰는 공통 타임아웃의
 * 단일 원천인데, 키 경로나 필드명이 틀려도 Spring 은 조용히 무시하고
 * 기본값(connect 10s / read 60s)으로 돌아간다 — 즉 "타임아웃이 안 걸린
 * 상태"가 아무 에러 없이 운영까지 나간다. 여기서 파싱과 바인딩을 잡는다.
 */
@DisplayName("Feign 공통 타임아웃 설정 바인딩")
class FeignResilienceConfigTest {

	private FeignClientProperties load() throws IOException {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader()
			.load("feign-resilience", new ClassPathResource("feign-resilience.yml"));
		return new Binder(ConfigurationPropertySources.from(sources.get(0)))
			.bind("spring.cloud.openfeign.client", Bindable.of(FeignClientProperties.class))
			.orElseThrow(() -> new IllegalStateException("spring.cloud.openfeign.client 바인딩 실패"));
	}

	@Test
	@DisplayName("default 타임아웃이 짧게 잡혀 있다")
	void defaultTimeouts() throws IOException {
		FeignClientProperties.FeignClientConfiguration config = load().getConfig().get("default");

		assertThat(config).isNotNull();
		assertThat(config.getConnectTimeout()).isEqualTo(1000);
		assertThat(config.getReadTimeout()).isEqualTo(3000);
	}

	@Test
	@DisplayName("모든 클라이언트 항목은 connect·read 타임아웃을 둘 다 가진다")
	void everyEntryHasBothTimeouts() throws IOException {
		// 동작상 필수는 아니다 — FeignClientFactoryBean 은 default 블록을 먼저
		// 적용한 뒤 클라이언트 블록을 덧씌우고, 없는 항목은 앞 단계 값을 그대로
		// 유지하므로 read-timeout 만 적어도 connect 는 default 의 1s 가 남는다.
		// 그래도 항목마다 두 값을 다 적게 강제하는 건, 유효 타임아웃을 상속을
		// 머릿속으로 계산하지 않고 그 줄에서 바로 읽기 위해서다.
		load().getConfig().forEach((name, config) -> {
			assertThat(config.getConnectTimeout())
				.as("%s 의 connect-timeout", name).isNotNull();
			assertThat(config.getReadTimeout())
				.as("%s 의 read-timeout", name).isNotNull();
		});
	}
}
