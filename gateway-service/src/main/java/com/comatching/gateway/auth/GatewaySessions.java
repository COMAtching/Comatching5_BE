package com.comatching.gateway.auth;

import java.util.List;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;

import com.comatching.common.auth.LoginSession;
import com.comatching.common.auth.SessionRedisScripts;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class GatewaySessions {
	private final ReactiveStringRedisTemplate redis;

	public Mono<Boolean> isActive(LoginSession session) {
		return redis.execute(SessionRedisScripts.IS_ACTIVE, List.of(LoginSession.versionKey(session.memberId()), session.sessionKey()),
				List.of(Long.toString(session.authVersion())))
			.single().map(result -> result == 1);
	}
}
