package com.comatching.user.domain.operations;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.comatching.common.auth.LoginSession;
import com.comatching.common.auth.TrustedSession;
import lombok.RequiredArgsConstructor;

/** Called while holding the LOGIN write lock. Drop each member's shared session generation. */
@Component
@RequiredArgsConstructor
public class MemberSessionShutdown {
	private final JdbcTemplate db;
	private final StringRedisTemplate redis;

	public void revoke() {
		long after = 0;
		while (true) {
			List<Long> ids = db.queryForList("""
				SELECT member_id FROM members WHERE member_id > ? AND role <> 'ROLE_ADMIN'
				ORDER BY member_id LIMIT 512
				""", Long.class, after);
			if (ids.isEmpty()) break;
			redis.executePipelined((RedisCallback<Object>) connection -> {
				for (long id : ids) {
					connection.keyCommands().del(LoginSession.versionKey(id).getBytes(StandardCharsets.UTF_8));
				}
				return null;
			});
			after = ids.get(ids.size() - 1);
		}
		redis.convertAndSend(TrustedSession.REVOCATIONS, "*:*");
	}
}
