package com.comatching.auth.domain.service.auth;

import com.comatching.common.dto.auth.SignupRequest;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;

import jakarta.servlet.http.HttpServletResponse;

public interface SignupService {

	// 계정 생성
	void signup(SignupRequest request);

	// 프로필 + 토큰 갱신
	ProfileResponse completeSignup(MemberInfo memberInfo, ProfileCreateRequest request, HttpServletResponse response);
}
