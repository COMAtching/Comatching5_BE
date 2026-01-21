package com.comatching.auth.infra.controller;

import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.auth.domain.dto.ChangePasswordRequest;
import com.comatching.auth.domain.dto.CompleteSignupResponse;
import com.comatching.auth.domain.dto.PasswordResetCodeRequest;
import com.comatching.auth.domain.dto.ResetPasswordRequest;
import com.comatching.auth.domain.dto.TokenResponse;
import com.comatching.auth.domain.service.auth.AuthService;
import com.comatching.auth.domain.service.auth.SignupService;
import com.comatching.auth.domain.service.mail.EmailService;
import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.auth.SignupRequest;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.util.CookieUtil;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;
	private final SignupService signupService;
	private final EmailService emailService;

	@PostMapping("/signup")
	public ResponseEntity<ApiResponse<Void>> signup(@RequestBody @Valid SignupRequest request) {
		signupService.signup(request);
		return ResponseEntity.ok(ApiResponse.ok());
	}

	@PostMapping("/signup/profile")
	public ResponseEntity<ApiResponse<CompleteSignupResponse>> completeSignup(
		@CurrentMember MemberInfo memberInfo,
		@RequestBody ProfileCreateRequest request,
		HttpServletResponse response
	) {
		CompleteSignupResponse result = signupService.completeSignup(memberInfo, request, response);
		return ResponseEntity.ok(ApiResponse.ok(result));
	}

	@PostMapping("/reissue")
	public ResponseEntity<ApiResponse<Void>> reissue(
		@CookieValue(name = "refreshToken") String refreshToken,
		HttpServletResponse response
	) {

		TokenResponse tokenResponse = authService.reissue(refreshToken);

		ResponseCookie accessCookie = CookieUtil.createAccessTokenCookie(tokenResponse.accessToken());
		ResponseCookie refreshCookie = CookieUtil.createRefreshTokenCookie(tokenResponse.refreshToken());

		response.addHeader("Set-Cookie", accessCookie.toString());
		response.addHeader("Set-Cookie", refreshCookie.toString());

		return ResponseEntity.ok(ApiResponse.ok());
	}

	@PostMapping("/logout")
	public ResponseEntity<ApiResponse<Void>> logout(
		@CookieValue(name = "refreshToken", required = false) String refreshToken,
		HttpServletResponse response) {

		authService.logout(refreshToken);

		ResponseCookie accessCookie = CookieUtil.createExpiredCookie("accessToken");
		ResponseCookie refreshCookie = CookieUtil.createExpiredCookie("refreshToken");

		response.addHeader("Set-Cookie", accessCookie.toString());
		response.addHeader("Set-Cookie", refreshCookie.toString());

		return ResponseEntity.ok(ApiResponse.ok());
	}

	@PostMapping("/password/code")
	public ResponseEntity<ApiResponse<Void>> sendPasswordResetCode(@RequestBody PasswordResetCodeRequest request) {
		emailService.sendPasswordResetCode(request.email());
		return ResponseEntity.ok(ApiResponse.ok());
	}

	@PatchMapping("/password/code")
	public ResponseEntity<ApiResponse<Void>> resetPassword(@RequestBody ResetPasswordRequest request) {
		authService.resetPassword(request);

		return ResponseEntity.ok(ApiResponse.ok());
	}

	@RequireRole({MemberRole.ROLE_ADMIN, MemberRole.ROLE_USER})
	@PatchMapping("/password/change")
	public ResponseEntity<ApiResponse<Void>> changePassword(
		@CurrentMember MemberInfo memberInfo,
		@RequestBody @Valid ChangePasswordRequest request
	) {
		authService.changePassword(memberInfo.memberId(), request);
		return ResponseEntity.ok(ApiResponse.ok());
	}

	@RequireRole(MemberRole.ROLE_USER)
	@DeleteMapping("/withdraw")
	public ResponseEntity<ApiResponse<Void>> withdraw(
		@CurrentMember MemberInfo memberInfo,
		HttpServletResponse response
	) {
		authService.withdraw(memberInfo.memberId());

		ResponseCookie accessCookie = CookieUtil.createExpiredCookie("accessToken");
		ResponseCookie refreshCookie = CookieUtil.createExpiredCookie("refreshToken");

		response.addHeader("Set-Cookie", accessCookie.toString());
		response.addHeader("Set-Cookie", refreshCookie.toString());

		return ResponseEntity.ok(ApiResponse.ok());
	}
}
