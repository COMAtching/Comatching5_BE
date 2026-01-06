package com.comatching.auth.infra.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.auth.domain.service.mail.EmailService;
import com.comatching.common.dto.auth.EmailRequest;
import com.comatching.common.dto.auth.EmailVerifyRequest;
import com.comatching.common.dto.response.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth/email")
@RequiredArgsConstructor
public class EmailController {

	private final EmailService emailService;

	@PostMapping("/send")
	public ResponseEntity<ApiResponse<Void>> sendEmail(@RequestBody EmailRequest request) {
		emailService.sendAuthCode(request.email());
		return ResponseEntity.ok(ApiResponse.ok());
	}

	@PostMapping("/verify")
	public ResponseEntity<ApiResponse<Void>> verifyEmail(@RequestBody EmailVerifyRequest request) {
		emailService.verifyCode(request.email(), request.code());
		return ResponseEntity.ok(ApiResponse.ok());
	}


}
