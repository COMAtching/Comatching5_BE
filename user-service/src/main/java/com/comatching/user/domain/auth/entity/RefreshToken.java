package com.comatching.user.domain.auth.entity;

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
