package com.comatching.common.auth;

import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class SessionRedisScripts {
	private SessionRedisScripts() {}

	// A restored snapshot or promoted replica can contain a session deleted on the former primary.
	// Bind the entry to that process and its current primary replication history.
	public static final String SESSION_STAMP = """
		local runId = string.match(redis.call('INFO', 'server'), 'run_id:(%x+)')
		local replication = redis.call('INFO', 'replication')
		local historyId = string.match(replication, 'master_replid:(%x+)')
		if not runId or not historyId or string.match(replication, 'role:(%a+)') ~= 'master' then
			return redis.error_reply('Redis primary session identity unavailable')
		end
		local sessionStamp = ARGV[1] .. ':' .. runId .. ':' .. historyId
		""";

	public static final DefaultRedisScript<Long> IS_ACTIVE = new DefaultRedisScript<>(SESSION_STAMP + """
		local state = redis.call('MGET', KEYS[1], KEYS[2])
		if state[1] == ARGV[1] and state[2] == sessionStamp then return 1 end
		return 0
		""", Long.class);
}
