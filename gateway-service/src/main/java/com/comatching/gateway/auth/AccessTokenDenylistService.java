package com.comatching.gateway.auth;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.comatching.common.util.AccessTokenDenylistKeys;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class AccessTokenDenylistService {

	private final ReactiveStringRedisTemplate redisTemplate;

	public Mono<Boolean> isDenied(String tokenId) {
		if (!StringUtils.hasText(tokenId)) {
			return Mono.just(false);
		}

		return redisTemplate.hasKey(AccessTokenDenylistKeys.key(tokenId))
			.defaultIfEmpty(false);
	}
}
