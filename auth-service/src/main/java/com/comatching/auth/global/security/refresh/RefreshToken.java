package com.comatching.auth.global.security.refresh;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@RedisHash(value = "refresh_token", timeToLive = 604800)
public class RefreshToken {

	@Id
	private Long memberId;

	private String token;
}
