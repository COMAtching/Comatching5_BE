package com.comatching.item.infra.bankdeposit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class CollectorCredential {
    private final byte[] expected;
    CollectorCredential(String token) {
        if (token.isBlank() || token.trim().length()<32) throw new IllegalArgumentException("Configure a dedicated deposit collector token (32+ characters)");
        expected=("Bearer "+token).getBytes(StandardCharsets.UTF_8);
    }
    void check(String header) {
        if(!MessageDigest.isEqual(expected,header.getBytes(StandardCharsets.UTF_8))) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
