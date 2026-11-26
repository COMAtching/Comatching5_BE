package com.comatching.notification.infra.controller;

import com.comatching.common.auth.InstallationCookie;
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
	@org.springframework.beans.factory.annotation.Value("${internal.service-token:}") private String gatewaySecret;

	@PostMapping("/token")
	public ResponseEntity<ApiResponse<Void>> registerToken(
		@RequestHeader HttpHeaders headers,
		@CookieValue(value = PushInstallations.COOKIE, required = false) String installation,
		@RequestBody FCMTokenRequest token
	) {
		fcmService.register(InstallationCookie.verify(installation, gatewaySecret), TrustedSession.from(headers, gatewaySecret), token.token());
		return ResponseEntity.ok(ApiResponse.ok());
	}
}
