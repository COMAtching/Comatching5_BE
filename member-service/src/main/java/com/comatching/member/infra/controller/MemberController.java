package com.comatching.member.infra.controller;

import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.util.CookieUtil;
import com.comatching.member.domain.service.member.external.MemberWithdrawService;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class MemberController {

	private final MemberWithdrawService memberWithdrawService;

	@RequireRole(MemberRole.ROLE_USER)
	@DeleteMapping("/members/withdraw")
	public ResponseEntity<ApiResponse<Void>> withdrawMember(
		@CurrentMember MemberInfo memberInfo,
		HttpServletResponse response) {
		memberWithdrawService.withdrawMember(memberInfo.memberId());

		ResponseCookie accessCookie = CookieUtil.createExpiredCookie("accessToken");
		ResponseCookie refreshCookie = CookieUtil.createExpiredCookie("refreshToken");

		response.addHeader("Set-Cookie", accessCookie.toString());
		response.addHeader("Set-Cookie", refreshCookie.toString());

		return ResponseEntity.ok(ApiResponse.ok());
	}
}
