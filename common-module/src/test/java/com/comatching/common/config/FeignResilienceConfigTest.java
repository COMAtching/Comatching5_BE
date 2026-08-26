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
		// OpenFeign 은 한 config 블록에 두 타임아웃이 모두 있어야 Options 를
		// 만든다. 하나만 적힌 항목은 그 블록 전체가 무시되어 기본 60s 로
		// 조용히 돌아가므로, 항목을 추가·수정할 때의 회귀를 여기서 막는다.
		load().getConfig().forEach((name, config) -> {
			assertThat(config.getConnectTimeout())
				.as("%s 의 connect-timeout", name).isNotNull();
			assertThat(config.getReadTimeout())
				.as("%s 의 read-timeout", name).isNotNull();
		});
	}
}
