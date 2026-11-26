package com.comatching.common.auth;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.UUID;

import org.springframework.http.HttpHeaders;

public record TrustedSession(LoginSession login, Instant expiresAt) {
    private static final String SIGNATURE = "X-Gateway-Session-Proof";
    public static final String SESSION = "X-Login-Session";
    public static final String VERSION = "X-Login-Version";
    public static final String EXPIRY = "X-Login-Expires";
    public static final String REVOCATIONS = "auth:v2:revocations";

    public void write(HttpHeaders headers, String secret) {
        headers.set(SESSION, login.id().toString());
        headers.set(VERSION, Long.toString(login.authVersion()));
        headers.set(EXPIRY, Long.toString(expiresAt.toEpochMilli()));
        headers.remove(SIGNATURE);
        if (secret != null && !secret.isBlank()) {
            headers.set(SIGNATURE, Base64.getUrlEncoder().withoutPadding().encodeToString(signature(headers, secret)));
        }
    }

    private static byte[] signature(HttpHeaders headers, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String values = String.join("\n", "ws-session-v1", headers.getFirst("X-Member-Id"),
                    headers.getFirst(SESSION), headers.getFirst(VERSION), headers.getFirst(EXPIRY),
                    headers.getFirst("X-Member-Role"), headers.getFirst("X-Member-Nickname"));
            return mac.doFinal(values.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    public static TrustedSession from(HttpHeaders headers, String secret) {
        if (secret == null || secret.isBlank() || headers.getFirst(SIGNATURE) == null) return null;
        try {
            if (!MessageDigest.isEqual(Base64.getUrlDecoder().decode(headers.getFirst(SIGNATURE)), signature(headers, secret))) return null;
            long memberId = Long.parseLong(headers.getFirst("X-Member-Id"));
            long version = Long.parseLong(headers.getFirst(VERSION));
            var session = new LoginSession(memberId, version, UUID.fromString(headers.getFirst(SESSION)));
            return new TrustedSession(session, Instant.ofEpochMilli(Long.parseLong(headers.getFirst(EXPIRY))));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return null;
        }
    }
}
