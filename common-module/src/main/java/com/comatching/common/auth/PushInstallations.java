package com.comatching.common.auth;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** A login claims its browser installation even before Firebase issues a token. */
public final class PushInstallations {
    public static final String COOKIE = "pushInstallation";
    public static final Duration LIFETIME = Duration.ofDays(60);
    private static final String PREFIX = "push:v2:{installations}:";
    private static final String REMOVE = """
        local function remove(id)
            local key = ARGV[1] .. 'i:' .. id
            local member = redis.call('HGET', key, 'member')
            local digest = redis.call('HGET', key, 'digest')
            if member then redis.call('ZREM', ARGV[1] .. 'm:' .. member, id) end
            if digest then
                local reverse = ARGV[1] .. 't:' .. digest
                if redis.call('GET', reverse) == id then redis.call('DEL', reverse) end
            end
            redis.call('DEL', key)
        end
        """;
    private static final DefaultRedisScript<String> BIND = new DefaultRedisScript<>(REMOVE + """
        local id = ARGV[2]
        local key = KEYS[1]
        -- One installation per login. A late bootstrap from a superseded login cannot take the installation back.
        if ARGV[8] == 'existing' then
            local assigned = redis.call('GET', KEYS[3])
            if assigned then
                id = assigned
                key = ARGV[1] .. 'i:' .. id
                if redis.call('HGET', key, 'sid') ~= ARGV[4] then return '' end
            end
        end
        local owner = redis.call('HGET', key, 'sid')
        if ARGV[8] == 'login' and (owner or '') ~= ARGV[9] then return 'retry' end
        if ARGV[8] == 'existing' and owner and owner ~= ARGV[4] then return '' end
        if owner ~= ARGV[4] then remove(id) end
        redis.call('HSET', key, 'member', ARGV[3], 'sid', ARGV[4], 'version', ARGV[5])
        redis.call('EXPIRE', key, ARGV[6])
        redis.call('SET', KEYS[3], id, 'EX', ARGV[6])
        redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', ARGV[7])
        redis.call('ZADD', KEYS[2], tonumber(ARGV[7]) + tonumber(ARGV[6]), id)
        redis.call('EXPIRE', KEYS[2], ARGV[6])
        return id
        """, String.class);
    private static final DefaultRedisScript<Long> REGISTER = new DefaultRedisScript<>("""
        if redis.call('HGET', KEYS[1], 'sid') ~= ARGV[3] or redis.call('HGET', KEYS[1], 'member') ~= ARGV[6]
                or redis.call('HGET', KEYS[1], 'version') ~= ARGV[7] then return 0 end
        local other = redis.call('GET', KEYS[2])
        if other and other ~= ARGV[2] and redis.call('HGET', ARGV[1] .. 'i:' .. other, 'digest') == ARGV[5] then
            return -1
        end
        local oldDigest = redis.call('HGET', KEYS[1], 'digest')
        if oldDigest and oldDigest ~= ARGV[5] then
            local oldReverse = ARGV[1] .. 't:' .. oldDigest
            if redis.call('GET', oldReverse) == ARGV[2] then redis.call('DEL', oldReverse) end
        end
        redis.call('HSET', KEYS[1], 'token', ARGV[4], 'digest', ARGV[5])
        redis.call('SET', KEYS[2], ARGV[2], 'EX', redis.call('TTL', KEYS[1]) + 1)
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> REVOKE = new DefaultRedisScript<>(REMOVE + """
        for _,id in ipairs(redis.call('ZRANGE', KEYS[1], 0, -1)) do
            local key = ARGV[1] .. 'i:' .. id
            local member = redis.call('HGET', key, 'member')
            if member == ARGV[2] and (ARGV[3] == '' or redis.call('HGET', key, 'sid') == ARGV[3]) then
                remove(id)
            elseif not member then
                redis.call('ZREM', KEYS[1], id)
            end
        end
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> INVALIDATE = new DefaultRedisScript<>("""
        if redis.call('HGET', KEYS[1], 'sid') ~= ARGV[1] or redis.call('HGET', KEYS[1], 'digest') ~= ARGV[2] then return 0 end
        redis.call('HDEL', KEYS[1], 'token', 'digest')
        if redis.call('GET', KEYS[2]) == ARGV[3] then redis.call('DEL', KEYS[2]) end
        return 1
        """, Long.class);

    private static final DefaultRedisScript<Long> SET_PREFERENCE = new DefaultRedisScript<>("""
        if redis.call('HGET', KEYS[1], 'sid') ~= ARGV[1] then return 0 end
        redis.call('HSET', KEYS[1], 'muted', ARGV[2])
        return 1
        """, Long.class);

    private final StringRedisTemplate redis;

    public PushInstallations(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record Target(UUID installation, LoginSession session, String token) {}

    public UUID bind(UUID installation, LoginSession session, boolean newLogin) {
        for (int attempt = 0; attempt < 8; attempt++) {
            LoginSession previous = newLogin ? owner(installation) : null;
            if (previous != null && !previous.equals(session)) {
                // Each member has a separate cluster slot. Invalidate the old login before the ownership CAS.
                SessionRedisScripts.end(redis, previous);
            }
            String expected = previous == null ? "" : previous.id().toString();
            String result = redis.execute(BIND, List.of(installKey(installation), memberKey(session.memberId()), PREFIX + "s:" + session.id()),
                    PREFIX, installation.toString(), Long.toString(session.memberId()), session.id().toString(),
                    Long.toString(session.authVersion()), Long.toString(LIFETIME.toSeconds()),
                    Long.toString(Instant.now().getEpochSecond()), newLogin ? "login" : "existing", expected);
            if (!"retry".equals(result)) return result == null || result.isEmpty() ? null : UUID.fromString(result);
        }
        throw new com.comatching.common.exception.BusinessException(
                com.comatching.common.exception.code.GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
    }

    private LoginSession owner(UUID installation) {
        var values = redis.opsForHash().multiGet(installKey(installation), List.of("member", "version", "sid"));
        if (values.get(0) == null) return null;
        return new LoginSession(Long.parseLong((String) values.get(0)), Long.parseLong((String) values.get(1)),
                UUID.fromString((String) values.get(2)));
    }

    public boolean register(UUID installation, LoginSession session, String token) {
        String digest = digest(token);
        if (!isActive(session)) return false;
        Long result = redis.execute(REGISTER, List.of(installKey(installation), PREFIX + "t:" + digest),
                PREFIX, installation.toString(), session.id().toString(), token, digest,
                Long.toString(session.memberId()), Long.toString(session.authVersion()));
        if (Long.valueOf(-1).equals(result)) {
            throw new com.comatching.common.exception.BusinessException(
                    com.comatching.common.exception.code.GeneralErrorCode.PUSH_TOKEN_IN_USE);
        }
        return Long.valueOf(1).equals(result);
    }

    public Set<String> installations(long memberId) {
        Set<String> ids = redis.opsForZSet().rangeByScore(memberKey(memberId), Instant.now().getEpochSecond() + 1, Double.POSITIVE_INFINITY);
        return ids == null ? Set.of() : ids;
    }

    public Target target(UUID installation, long memberId) {
        Map<Object, Object> data = redis.opsForHash().entries(installKey(installation));
        String token = (String)data.get("token");
        if (token == null || "1".equals(data.get("muted")) || !Long.toString(memberId).equals(data.get("member"))) return null;
        var session = new LoginSession(memberId, Long.parseLong((String)data.get("version")), UUID.fromString((String)data.get("sid")));
        return isActive(session) ? new Target(installation, session, token) : null;
    }

    public record Preference(boolean muted, boolean registered) {}

    public Preference preference(UUID installation, LoginSession login) {
        var fields = redis.opsForHash().multiGet(installKey(installation), List.of("sid", "muted", "digest"));
        if (!login.id().toString().equals(fields.get(0))) throw new BusinessException(GeneralErrorCode.UNAUTHORIZED);
        return new Preference("1".equals(fields.get(1)), fields.get(2) != null);
    }

    public void setEnabled(UUID installation, LoginSession login, boolean enabled) {
        Long saved = redis.execute(SET_PREFERENCE, List.of(installKey(installation)), login.id().toString(), enabled ? "0" : "1");
        if (!Long.valueOf(1).equals(saved)) throw new BusinessException(GeneralErrorCode.UNAUTHORIZED);
    }

    // A notification can outlive a login. Only that login on that installation can resolve its destination.
    public UUID rememberRoom(Target target, String roomId) {
        UUID reference = UUID.randomUUID();
        redis.opsForValue().set(linkKey(target.installation(), target.session(), reference), roomId, Duration.ofDays(28));
        return reference;
    }

    public String resolveRoom(UUID installation, LoginSession login, UUID reference) {
        return redis.opsForValue().get(linkKey(installation, login, reference));
    }

    private static String linkKey(UUID installation, LoginSession login, UUID reference) {
        return "push:v2:chat-link:" + installation + ":" + login.id() + ":" + reference;
    }

    public boolean isActive(LoginSession session) {
        return SessionRedisScripts.isActive(redis, session);
    }

    public void revoke(long memberId, UUID sid) {
        redis.execute(REVOKE, List.of(memberKey(memberId)), PREFIX, Long.toString(memberId), sid == null ? "" : sid.toString());
    }

    public void invalidate(Target target) {
        String digest = digest(target.token());
        redis.execute(INVALIDATE, List.of(installKey(target.installation()), PREFIX + "t:" + digest),
                target.session().id().toString(), digest, target.installation().toString());
    }

    private static String memberKey(long id) { return PREFIX + "m:" + id; }
    private static String installKey(UUID id) { return PREFIX + "i:" + id; }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
