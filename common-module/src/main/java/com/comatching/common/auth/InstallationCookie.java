package com.comatching.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class InstallationCookie {
    private InstallationCookie() {}

    public static String sign(UUID id, String secret) {
        return id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(id, secret));
    }

    public static UUID verify(String value, String secret) {
        if (value == null || value.length() != 80 || value.charAt(36) != '.' || secret == null || secret.isBlank()) return null;
        try {
            UUID id = UUID.fromString(value.substring(0, 36));
            byte[] supplied = Base64.getUrlDecoder().decode(value.substring(37));
            return MessageDigest.isEqual(supplied, mac(id, secret)) ? id : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static byte[] mac(UUID id, String secret) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hmac.doFinal(("push-installation-v1:" + id).getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
