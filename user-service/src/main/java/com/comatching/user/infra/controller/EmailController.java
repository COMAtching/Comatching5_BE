package com.comatching.user.infra.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.user.domain.mail.service.EmailService;
import com.comatching.common.dto.auth.EmailRequest;
import com.comatching.common.dto.auth.EmailVerifyRequest;
import com.comatching.common.dto.response.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth/email")
@RequiredArgsConstructor
public class EmailController {

	private final EmailService emailService;

	@PostMapping("/send")
	public ResponseEntity<ApiResponse<Void>> sendEmail(@RequestBody @Valid EmailRequest request) {
		emailService.sendAuthCode(request.email());
		return ResponseEntity.ok(ApiResponse.ok());
	}

	@PostMapping("/verify")
	public ResponseEntity<ApiResponse<Void>> verifyEmail(@RequestBody @Valid EmailVerifyRequest request) {
		emailService.verifyCode(request.email(), request.code());
		return ResponseEntity.ok(ApiResponse.ok());
	}


}
