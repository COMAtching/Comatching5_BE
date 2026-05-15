package com.comatching.user.domain.auth.service;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.comatching.common.util.AccessTokenDenylistKeys;
import com.comatching.common.util.JwtUtil;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccessTokenDenylistService {

	private final JwtUtil jwtUtil;
	private final StringRedisTemplate redisTemplate;

	public void revoke(String accessToken) {
		if (!StringUtils.hasText(accessToken)) {
			return;
		}

		try {
			Claims claims = jwtUtil.parseToken(accessToken);
			String tokenId = claims.getId();
			if (!StringUtils.hasText(tokenId)) {
				log.debug("Skip access token revoke because token has no jti.");
				return;
			}

			long ttlMillis = Math.max(0, claims.getExpiration().getTime() - System.currentTimeMillis());
			if (ttlMillis == 0) {
				return;
			}

			redisTemplate.opsForValue()
				.set(AccessTokenDenylistKeys.key(tokenId), "1", Duration.ofMillis(ttlMillis));
		} catch (Exception e) {
			log.warn("Access token revoke failed. message={}", e.getMessage());
		}
	}
}
