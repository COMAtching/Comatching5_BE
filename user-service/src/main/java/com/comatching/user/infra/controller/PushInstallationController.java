package com.comatching.user.infra.controller;

import com.comatching.common.auth.PushInstallations;
import com.comatching.common.auth.TrustedSession;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.user.domain.auth.service.LoginSessionService;
import com.comatching.user.global.security.cookie.AuthCookieFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class PushInstallationController {
    private final LoginSessionService sessions;
    private final AuthCookieFactory cookies;
    @org.springframework.beans.factory.annotation.Value("${internal.service-token:}") private String gatewaySecret;

    // Also upgrades users whose login predates the installation cookie.
    @PostMapping("/api/members/push-installation")
    public ResponseEntity<Void> prepare(@RequestHeader HttpHeaders headers,
            @CookieValue(value = PushInstallations.COOKIE, required = false) String cookie) {
        TrustedSession identity = TrustedSession.from(headers, gatewaySecret);
        if (identity == null || !identity.expiresAt().isAfter(java.time.Instant.now())) throw new BusinessException(GeneralErrorCode.UNAUTHORIZED);
        if (!cookies.canIssueInstallation()) throw new BusinessException(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
        var previous = cookies.installationId(cookie);
        var id = sessions.prepareInstallation(previous == null ? java.util.UUID.randomUUID() : previous, identity.login());
        var response = ResponseEntity.noContent();
        if (!id.equals(previous)) response.header(HttpHeaders.SET_COOKIE, cookies.installation(id).toString());
        return response.build();
    }
}
