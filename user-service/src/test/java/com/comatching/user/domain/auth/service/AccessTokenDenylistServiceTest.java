package com.comatching.user.domain.auth.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.comatching.common.util.AccessTokenDenylistKeys;
import com.comatching.common.util.JwtUtil;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccessTokenDenylistService 테스트")
class AccessTokenDenylistServiceTest {

	private static final String SECRET = Base64.getEncoder()
		.encodeToString("01234567890123456789012345678912".getBytes(StandardCharsets.UTF_8));

	private final JwtUtil jwtUtil = new JwtUtil(SECRET, 3600000L, 3600000L);

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	private AccessTokenDenylistService accessTokenDenylistService;

	@BeforeEach
	void setUp() {
		accessTokenDenylistService = new AccessTokenDenylistService(jwtUtil, redisTemplate);
	}

	@Test
	@DisplayName("access token jti를 남은 만료시간만큼 Redis denylist에 저장한다")
	void shouldStoreAccessTokenJtiInRedisDenylist() {
		// given
		String token = jwtUtil.createAccessToken(100L, "test@example.com", "ROLE_USER", "ACTIVE", "tester");
		String tokenId = jwtUtil.parseToken(token).getId();
		given(redisTemplate.opsForValue()).willReturn(valueOperations);

		// when
		accessTokenDenylistService.revoke(token);

		// then
		then(valueOperations).should()
			.set(eq(AccessTokenDenylistKeys.key(tokenId)), eq("1"), any(Duration.class));
	}
}
