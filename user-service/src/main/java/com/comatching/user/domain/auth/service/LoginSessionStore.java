package com.comatching.user.domain.auth.service;

import java.util.List;
import java.util.UUID;
import com.comatching.common.auth.PushInstallations;
import com.comatching.common.auth.TrustedSession;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.comatching.common.auth.LoginSession;
import com.comatching.common.auth.SessionRedisScripts;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

@Component
public class LoginSessionStore {
	private static final String SAVE = """
		redis.call('SET', KEYS[1], generation, 'PX', ARGV[3])
		redis.call('SET', KEYS[2], sessionValue(generation), 'PX', ARGV[3])
		redis.call('SET', KEYS[3], ARGV[2], 'PX', ARGV[4])
		return 1
		""";
	private static final DefaultRedisScript<Long> START = new DefaultRedisScript<>(SessionRedisScripts.SESSION_STAMP + """
		local generation = redis.call('GET', KEYS[1])
		if not matchesVersion(generation) then generation = ARGV[1] .. '/' .. string.match(ARGV[2], '^[^:]+') end
		""" + SAVE, Long.class);
	private static final DefaultRedisScript<Long> ROTATE = new DefaultRedisScript<>(SessionRedisScripts.SESSION_STAMP + """
		local generation = redis.call('GET', KEYS[1])
		if not matchesVersion(generation) or redis.call('GET', KEYS[2]) ~= sessionValue(generation) then return 0 end
		local current = redis.call('GET', KEYS[3])
		if not current then return 0 end
		if ARGV[6] == 'onboarding' then
			if string.sub(current, 1, #ARGV[5]) ~= ARGV[5] then return 0 end
		elseif current ~= ARGV[5] then
			return 0
		end
		""" + SAVE, Long.class);
	private static final DefaultRedisScript<Long> END_ALL = new DefaultRedisScript<>("return redis.call('DEL', KEYS[1], KEYS[2])", Long.class);

	private final StringRedisTemplate redis;
	private final PushInstallations installations;
	private final String sessionTtl;
	private final String refreshTtl;

	public LoginSessionStore(StringRedisTemplate redis, @Value("${jwt.access-token.expiration}") long accessMillis,
		@Value("${jwt.refresh-token.expiration}") long refreshMillis) {
		this.redis = redis;
		this.installations = new PushInstallations(redis);
		this.sessionTtl = Long.toString(Math.max(accessMillis, refreshMillis));
		this.refreshTtl = Long.toString(refreshMillis);
	}

	void start(LoginSession session, String newTokenId) {
		execute(START, keys(session), Long.toString(session.authVersion()), credential(session, newTokenId), sessionTtl, refreshTtl);
	}

	UUID claimInstallation(UUID installation, LoginSession session, boolean newLogin) {
		try {
			if (!installations.isActive(session)) throw new BusinessException(GeneralErrorCode.UNAUTHORIZED);
			UUID bound = installations.bind(installation, session, newLogin);
			if (bound == null) throw new BusinessException(GeneralErrorCode.UNAUTHORIZED);
			return bound;
		} catch (DataAccessException unavailable) {
			throw new BusinessException(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
		}
	}

	void rotate(LoginSession session, String previousTokenId, String newTokenId) {
		replace(session, newTokenId, credential(session, previousTokenId), "refresh");
	}

	void upgrade(LoginSession session, String newTokenId) {
		replace(session, newTokenId, session.id() + ":", "onboarding");
	}

	private void replace(LoginSession session, String newTokenId, String expected, String operation) {
		long updated = execute(ROTATE, keys(session), Long.toString(session.authVersion()),
			credential(session, newTokenId), sessionTtl, refreshTtl, expected, operation);
		if (updated == 0) throw new BusinessException(GeneralErrorCode.UNAUTHORIZED);
	}

	boolean end(LoginSession session) {
		try {
			boolean ended = SessionRedisScripts.end(redis, session);
			installations.revoke(session.memberId(), session.id());
			return ended;
		} catch (DataAccessException unavailable) {
			throw new BusinessException(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
		}
	}

	public void endAll(long memberId) {
		execute(END_ALL, List.of(LoginSession.versionKey(memberId), LoginSession.refreshKey(memberId)));
		revokeInstallations(memberId, null);
	}

	private void revokeInstallations(long memberId, UUID sid) {
		try {
			installations.revoke(memberId, sid);
			redis.convertAndSend(TrustedSession.REVOCATIONS, memberId + ":" + (sid == null ? "*" : sid));
		} catch (DataAccessException unavailable) {
			throw new BusinessException(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
		}
	}

	private static List<String> keys(LoginSession session) {
		return List.of(LoginSession.versionKey(session.memberId()), session.sessionKey(), LoginSession.refreshKey(session.memberId()));
	}

	private static String credential(LoginSession session, String tokenId) {
		return session.id() + ":" + tokenId;
	}

	private long execute(DefaultRedisScript<Long> script, List<String> keys, String... args) {
		try {
			Long result = redis.execute(script, keys, (Object[])args);
			if (result == null) throw new BusinessException(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
			return result;
		} catch (DataAccessException e) {
			throw new BusinessException(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
		}
	}
}
