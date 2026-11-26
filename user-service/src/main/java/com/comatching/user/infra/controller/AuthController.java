package com.comatching.user.infra.controller;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.user.domain.auth.dto.ChangePasswordRequest;
import com.comatching.user.domain.auth.dto.CompleteSignupResponse;
import com.comatching.user.domain.auth.dto.NicknameAvailabilityResponse;
import com.comatching.user.domain.auth.dto.PasswordResetCodeRequest;
import com.comatching.user.domain.auth.dto.ParticipantCountResponse;
import com.comatching.user.domain.auth.dto.ResetPasswordRequest;
import com.comatching.user.domain.auth.dto.TokenResponse;
import com.comatching.user.domain.auth.service.AuthService;
import com.comatching.user.domain.auth.service.SignupService;
import com.comatching.user.domain.mail.service.EmailService;
import com.comatching.user.domain.member.service.MemberService;
import com.comatching.user.domain.member.service.ProfileManageService;
import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.auth.LoginSession;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.auth.SignupRequest;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.util.JwtUtil;
import com.comatching.user.global.security.cookie.AuthCookieFactory;

import jakarta.servlet.http.HttpServletRequest;
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
	private final MailRequestSource mailSource;
	private final ProfileManageService profileManageService;
	private final MemberService memberService;
	private final AuthCookieFactory authCookieFactory;
	private final JwtUtil jwt;

	@PostMapping("/signup")
	public ResponseEntity<ApiResponse<Void>> signup(@RequestBody @Valid SignupRequest request,
		@CookieValue(name = AuthCookieFactory.SIGNUP_PROOF, required = false) String proof) {
		signupService.signup(request, proof);
		return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
			authCookieFactory.signupProof("", Duration.ZERO).toString()).body(ApiResponse.ok());
	}

	@GetMapping("/session")
	public ResponseEntity<String> currentSession(@CookieValue("accessToken") String access) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(jwt.readAccess(access).session().clientMarker());
	}

	@PostMapping("/signup/profile")
	public ResponseEntity<ApiResponse<CompleteSignupResponse>> completeSignup(
		@CurrentMember MemberInfo memberInfo,
		@RequestBody ProfileCreateRequest request,
		@RequestHeader("X-Onboarding-Session") String owner,
		@CookieValue(name = "accessToken", required = false) String accessToken,
		HttpServletResponse response
	) {
		if (!jwt.readAccess(accessToken).session().clientMarker().equals(owner)) throw new BusinessException(GeneralErrorCode.FORBIDDEN);
		var result = signupService.completeSignup(memberInfo, request, accessToken);
		authCookieFactory.writeTokens(response, result.tokens());
		return ResponseEntity.ok(ApiResponse.ok(result.body()));
	}

	@GetMapping("/signup/nickname/availability")
	public ResponseEntity<ApiResponse<NicknameAvailabilityResponse>> checkNicknameAvailability(
		@RequestParam String nickname
	) {
		boolean available = profileManageService.isNicknameAvailable(nickname);
		return ResponseEntity.ok(ApiResponse.ok(new NicknameAvailabilityResponse(available)));
	}

	@GetMapping("/participants")
	public ResponseEntity<ApiResponse<ParticipantCountResponse>> getParticipantCount() {
		long count = memberService.getActiveUserCount();
		return ResponseEntity.ok(ApiResponse.ok(new ParticipantCountResponse(count)));
	}

	@PostMapping("/reissue")
	public ResponseEntity<ApiResponse<Void>> reissue(
		@CookieValue(name = "refreshToken", required = false) String refreshToken,
		@RequestHeader(value = LoginSession.EXPECTED_HEADER, required = false) String expected,
		HttpServletResponse response
	) {
		if (!StringUtils.hasText(refreshToken)) {
			throw new BusinessException(GeneralErrorCode.UNAUTHORIZED, "리프레시 토큰이 없습니다. 다시 로그인해주세요.");
		}

		if (expected != null && !expected.equals(jwt.readRefresh(refreshToken).session().clientMarker())) {
			throw new BusinessException(GeneralErrorCode.FORBIDDEN);
		}
		TokenResponse tokenResponse = authService.reissue(refreshToken);

		authCookieFactory.writeTokens(response, tokenResponse);

		return ResponseEntity.ok(ApiResponse.ok());
	}

	@PostMapping("/logout")
	public ResponseEntity<ApiResponse<Void>> logout(
		@CookieValue(name = "accessToken", required = false) String accessToken,
		@CookieValue(name = "refreshToken", required = false) String refreshToken,
		@RequestHeader(value = LoginSession.EXPECTED_HEADER, required = false) String expected,
		HttpServletResponse response) {

		boolean ended = authService.logout(accessToken, refreshToken, expected);
		// A delayed response must not expire the next account's cookies in the same browser.
		// Legacy callers still expect cookie expiry when they actually close a live session.
		if (ended && expected == null) expireLegacyCookies(response);

		return ResponseEntity.ok(ApiResponse.ok());
	}

	@PostMapping("/password/code")
	public ResponseEntity<ApiResponse<Void>> sendPasswordResetCode(@RequestBody @Valid PasswordResetCodeRequest request,
		HttpServletRequest servletRequest) {
		emailService.sendPasswordResetCode(request.email(), mailSource.of(servletRequest));
		return ResponseEntity.ok(ApiResponse.ok());
	}

	@PatchMapping("/password/code")
	public ResponseEntity<ApiResponse<Void>> resetPassword(@RequestBody @Valid ResetPasswordRequest request) {
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
		@RequestHeader(value = LoginSession.EXPECTED_HEADER, required = false) String expected,
		HttpServletResponse response
	) {
		authService.withdraw(memberInfo.memberId());
		if (expected == null) expireLegacyCookies(response);
		return ResponseEntity.ok(ApiResponse.ok());
	}

	private void expireLegacyCookies(HttpServletResponse response) {
		response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.createExpiredCookie("accessToken").toString());
		response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.createExpiredCookie("refreshToken").toString());
	}
}
