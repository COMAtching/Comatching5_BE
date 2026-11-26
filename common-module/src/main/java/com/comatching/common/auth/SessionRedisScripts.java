package com.comatching.common.auth;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class SessionRedisScripts {
	private SessionRedisScripts() {}

	// A restored snapshot or promoted replica can contain a session deleted on the former primary.
	// Bind the entry to that process and its current primary replication history.
	// Plain version keys support existing sessions. A replacement key has a fresh per-member generation
	// so creating a new login after a shutdown cannot revive previously issued session IDs.
	public static final String SESSION_STAMP = """
		local runId = string.match(redis.call('INFO', 'server'), 'run_id:(%x+)')
		local replication = redis.call('INFO', 'replication')
		local historyId = string.match(replication, 'master_replid:(%x+)')
		if not runId or not historyId or string.match(replication, 'role:(%a+)') ~= 'master' then
			return redis.error_reply('Redis primary session identity unavailable')
		end
		local sessionStamp = ARGV[1] .. ':' .. runId .. ':' .. historyId
		local function matchesVersion(value)
			return value == ARGV[1] or (value and string.sub(value, 1, #ARGV[1]+1) == ARGV[1] .. '/')
		end
		local function sessionValue(value)
			if value == ARGV[1] then return sessionStamp end
			return sessionStamp .. ':' .. value
		end
		""";

	public static boolean isActive(StringRedisTemplate redis, LoginSession session) {
		return Long.valueOf(1).equals(redis.execute(IS_ACTIVE, List.of(LoginSession.versionKey(session.memberId()), session.sessionKey()),
			Long.toString(session.authVersion())));
	}

	// The session keys share a Redis Cluster slot; subscribers see the deletion before its notification.
	private static final DefaultRedisScript<Long> END = new DefaultRedisScript<>("""
		local removed = redis.call('DEL', KEYS[1])
		local refresh = redis.call('GET', KEYS[2])
		if refresh and string.sub(refresh, 1, #ARGV[1]) == ARGV[1] then redis.call('DEL', KEYS[2]) end
		redis.call('PUBLISH', ARGV[2], ARGV[3])
		return removed
		""", Long.class);

	public static boolean end(StringRedisTemplate redis, LoginSession session) {
		return Long.valueOf(1).equals(redis.execute(END, List.of(session.sessionKey(), LoginSession.refreshKey(session.memberId())),
			session.id() + ":", TrustedSession.REVOCATIONS, session.memberId() + ":" + session.id()));
	}

	public static final DefaultRedisScript<Long> IS_ACTIVE = new DefaultRedisScript<>(SESSION_STAMP + """
		local state = redis.call('MGET', KEYS[1], KEYS[2])
		if matchesVersion(state[1]) and state[2] == sessionValue(state[1]) then return 1 end
		return 0
		""", Long.class);
}
