package com.comatching.user.global.config;

import java.time.Duration;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

@Configuration
@EnableCaching
public class CacheConfig {

	public static final String PARTICIPANT_COUNT = "participantCount";

	/**
	 * 참여자 수 COUNT 쿼리는 요청마다 ACTIVE 회원 수만큼 인덱스를 훑는다(회원 수에 비례).
	 * 값 자체는 표시용이라 몇 초 늦어도 되므로 짧은 TTL 로컬 캐시로 DB 부하를 끊는다.
	 * user-service 는 단일 인스턴스 전제라 인스턴스 간 정합성은 고려하지 않는다.
	 */
	@Bean
	public CacheManager cacheManager() {
		CaffeineCacheManager cacheManager = new CaffeineCacheManager(PARTICIPANT_COUNT);
		cacheManager.setCaffeine(Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofSeconds(10))
			.maximumSize(100));
		return cacheManager;
	}
}
