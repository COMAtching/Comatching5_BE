package com.comatching.notification.infra.controller;

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

	@PostMapping("/token")
	public ResponseEntity<ApiResponse<Void>> registerToken(
		@RequestHeader("X-Member-Id") Long memberId,
		@RequestBody FCMTokenRequest token
	) {
		fcmService.saveToken(memberId, token.token());
		return ResponseEntity.ok(ApiResponse.ok());
	}
}
