package com.comatching.user.domain.auth.service;

import com.comatching.user.domain.auth.dto.CompleteSignupResponse;
import com.comatching.common.dto.auth.SignupRequest;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.member.ProfileCreateRequest;

import com.comatching.user.domain.auth.dto.TokenResponse;

public interface SignupService {

	// 계정 생성
	void signup(SignupRequest request, String signupProof);

	// 프로필 + 토큰 갱신
	CompletedSignup completeSignup(MemberInfo memberInfo, ProfileCreateRequest request, String accessToken);

	record CompletedSignup(CompleteSignupResponse body, TokenResponse tokens) {}
}
