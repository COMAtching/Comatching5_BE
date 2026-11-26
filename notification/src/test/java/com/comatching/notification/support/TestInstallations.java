package com.comatching.notification.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import com.comatching.common.auth.LoginSession;
import com.comatching.common.auth.PushInstallations;
import com.comatching.common.auth.SessionRedisScripts;
import com.comatching.common.auth.TrustedSession;
import com.comatching.notification.domain.service.fcm.FCMService;

public final class TestInstallations {
    private TestInstallations() {}
    public static UUID device(long member) { return UUID.nameUUIDFromBytes(("device-" + member).getBytes(StandardCharsets.UTF_8)); }
    public static TrustedSession activate(StringRedisTemplate redis, UUID device, long member, UUID sid) {
        var session = new LoginSession(member, 0, sid);
        redis.execute(new DefaultRedisScript<>(SessionRedisScripts.SESSION_STAMP + """
                redis.call('SET', KEYS[1], ARGV[1], 'EX', 3600)
                redis.call('SET', KEYS[2], sessionStamp, 'EX', 3600)
                return 1
                """, Long.class), List.of(LoginSession.versionKey(member), session.sessionKey()), "0");
        new PushInstallations(redis).bind(device, session, true);
        return new TrustedSession(session, Instant.now().plusSeconds(3600));
    }
    public static void register(StringRedisTemplate redis, FCMService service, long member, String token) {
        UUID device = device(member);
        var owner = activate(redis, device, member, device);
        service.register(device, owner, token);
    }
}
