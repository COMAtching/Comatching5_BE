package com.comatching.user.domain.mail.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.user.domain.mail.enums.EmailType;

/** SMTP runs outside Redis. Only the sender holding the reservation may replace an active code. */
@Component
public class VerificationMailStore {
	private static final String PREFIX = "auth:mail:{limits}:";
	private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
		local wait = redis.call('TTL', KEYS[1])
		if wait > 60 then wait = 60 end
		for i = 2, 4 do
		    if tonumber(redis.call('GET', KEYS[i]) or 0) >= tonumber(ARGV[i]) then
		        wait = math.max(wait, redis.call('TTL', KEYS[i]), 1)
		    end
		end
		if wait >= 0 then return math.max(wait, 1) end
		redis.call('SET', KEYS[1], ARGV[1], 'EX', 120)
		local windows = {3600, 600, 60}
		for i = 2, 4 do
		    if redis.call('INCR', KEYS[i]) == 1 then redis.call('EXPIRE', KEYS[i], windows[i-1]) end
		end
		return 0
		""", Long.class);
	private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
		if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
		redis.call('HSET', KEYS[2], 'recipient', ARGV[2], 'code', ARGV[3])
		redis.call('EXPIRE', KEYS[2], 300)
		redis.call('SET', KEYS[1], 'sent', 'EX', 60)
		return 1
		""", Long.class);
	private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
		if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
		return 0
		""", Long.class);
	private static final DefaultRedisScript<Long> VERIFY = new DefaultRedisScript<>("""
		if redis.call('EXISTS', KEYS[1]) == 1 then
		    if redis.call('HGET', KEYS[1], 'recipient') == ARGV[1]
		        and redis.call('HGET', KEYS[1], 'code') == ARGV[2] then
		        redis.call('HDEL', KEYS[1], 'code')
		        return 1
		    end
		    return 0
		end
		-- Codes sent by the previous release expire within five minutes.
		if redis.call('GET', KEYS[2]) == ARGV[2] then return redis.call('DEL', KEYS[2]) end
		return 0
		""", Long.class);

	public record Reservation(String key, String activeKey, String id) {}

	private final StringRedisTemplate redis;
	private final int recipientLimit;
	private final int sourceLimit;
	private final int globalLimit;

	public VerificationMailStore(StringRedisTemplate redis,
		@Value("${comatching.mail.recipient-limit:6}") int recipientLimit,
		@Value("${comatching.mail.source-limit:120}") int sourceLimit,
		@Value("${comatching.mail.global-limit:120}") int globalLimit) {
		if (Math.min(Math.min(recipientLimit, sourceLimit), globalLimit) <= 0) {
			throw new IllegalArgumentException("Verification mail limits must be positive");
		}
		this.redis = redis;
		this.recipientLimit = recipientLimit;
		this.sourceLimit = sourceLimit;
		this.globalLimit = globalLimit;
	}

	private static String digest(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String scope(EmailType type, String email) {
		return PREFIX + type.name() + ":" + digest(email.trim().toLowerCase(Locale.ROOT));
	}

	public Reservation reserve(EmailType type, String email, String source) {
		String scope = scope(type, email);
		var reservation = new Reservation(scope + ":delivery", scope + ":active", UUID.randomUUID().toString());
		long retry = execute(RESERVE, List.of(reservation.key(),
				PREFIX + "recipient:" + digest(email.trim().toLowerCase(Locale.ROOT)),
				PREFIX + "source:" + digest(source), PREFIX + "global"),
			reservation.id(), Integer.toString(recipientLimit), Integer.toString(sourceLimit), Integer.toString(globalLimit));
		if (retry > 0) throw new VerificationMailLimitException(retry);
		return reservation;
	}

	public String previousCode(Reservation reservation, EmailType type, String email) {
		try {
			Object current = redis.opsForHash().get(reservation.activeKey(), "code");
			return current == null ? redis.opsForValue().get(type.getPrefix() + email) : current.toString();
		} catch (DataAccessException e) {
			throw new BusinessException(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
		}
	}

	public void publish(Reservation reservation, String email, String code) {
		if (execute(PUBLISH, List.of(reservation.key(), reservation.activeKey()), reservation.id(), email, code) != 1) {
			throw new BusinessException(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
		}
	}

	public void release(Reservation reservation) {
		execute(RELEASE, List.of(reservation.key()), reservation.id());
	}

	public boolean consume(EmailType type, String email, String code) {
		return execute(VERIFY, List.of(scope(type, email) + ":active", type.getPrefix() + email), email, code) == 1;
	}

	private long execute(DefaultRedisScript<Long> script, List<String> keys, String... args) {
		try {
			Long result = redis.execute(script, keys, (Object[])args);
			if (result != null) return result;
		} catch (DataAccessException ignored) {
			// Never dispatch mail when the reservation or limits cannot be checked.
		}
		throw new BusinessException(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
	}
}
