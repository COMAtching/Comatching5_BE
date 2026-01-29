package com.comatching.user.domain.auth.service;

import com.comatching.user.domain.auth.dto.CompleteSignupResponse;
import com.comatching.common.dto.auth.SignupRequest;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.member.ProfileCreateRequest;

import jakarta.servlet.http.HttpServletResponse;

public interface SignupService {

	// 계정 생성
	void signup(SignupRequest request);

	// 프로필 + 토큰 갱신
	CompleteSignupResponse completeSignup(MemberInfo memberInfo, ProfileCreateRequest request, HttpServletResponse response);
}
