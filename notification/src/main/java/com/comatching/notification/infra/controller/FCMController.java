package com.comatching.notification.infra.controller;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.beans.factory.annotation.Value;

import com.comatching.common.auth.LoginSession;
import com.comatching.common.auth.InstallationCookie;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpHeaders;
import com.comatching.common.auth.PushInstallations;
import com.comatching.common.auth.TrustedSession;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.dto.response.ApiResponse;
import com.comatching.notification.domain.dto.FCMTokenRequest;
import com.comatching.notification.domain.service.fcm.FCMService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/fcm")
public class FCMController {

    private final FCMService fcmService;
    private final PushInstallations installations;
    @Value("${internal.service-token:}") private String gatewaySecret;

    @PostMapping("/token")
    public ResponseEntity<ApiResponse<Void>> registerToken(
        @RequestHeader HttpHeaders headers,
        @CookieValue(value = PushInstallations.COOKIE, required = false) String installation,
        @RequestBody FCMTokenRequest token
    ) {
        fcmService.register(InstallationCookie.verify(installation, gatewaySecret), TrustedSession.from(headers, gatewaySecret), token.token());
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @GetMapping("/preference")
    public ResponseEntity<PushInstallations.Preference> preference(@RequestHeader HttpHeaders headers,
            @CookieValue(value = PushInstallations.COOKIE, required = false) String cookie) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(installations.preference(device(cookie), login(headers)));
    }

    public record PreferenceRequest(@NotNull Boolean enabled) {}

    @PutMapping("/preference")
    public ResponseEntity<Void> preference(@RequestHeader HttpHeaders headers,
            @CookieValue(value = PushInstallations.COOKIE, required = false) String cookie,
            @Valid @RequestBody PreferenceRequest request) {
        installations.setEnabled(device(cookie), login(headers), request.enabled());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/chat-links/{reference}")
    public ResponseEntity<String> resolve(@PathVariable UUID reference, @RequestHeader HttpHeaders headers,
            @CookieValue(value = PushInstallations.COOKIE, required = false) String cookie) {
        var room = installations.resolveRoom(device(cookie), login(headers), reference);
        return room == null ? ResponseEntity.notFound().build() : ResponseEntity.ok()
                .cacheControl(CacheControl.noStore()).body(room);
    }

    @GetMapping(value = "/chat-links/{reference}/sender", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> senderNickname(@PathVariable UUID reference, @RequestHeader HttpHeaders headers,
            @CookieValue(value = PushInstallations.COOKIE, required = false) String cookie) {
        String nickname = installations.resolveSenderNickname(device(cookie), login(headers), reference);
        return nickname == null ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
                : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(nickname);
    }

    private UUID device(String cookie) {
        UUID id = InstallationCookie.verify(cookie, gatewaySecret);
        if (id == null) throw new BusinessException(GeneralErrorCode.UNAUTHORIZED);
        return id;
    }

    private LoginSession login(HttpHeaders headers) {
        TrustedSession owner = TrustedSession.from(headers, gatewaySecret);
        if (owner == null || !owner.expiresAt().isAfter(Instant.now()) || !installations.isActive(owner.login()))
            throw new BusinessException(GeneralErrorCode.UNAUTHORIZED);
        return owner.login();
    }
}
