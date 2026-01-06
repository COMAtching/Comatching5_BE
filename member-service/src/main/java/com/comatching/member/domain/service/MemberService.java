package com.comatching.member.domain.service;

import com.comatching.common.dto.auth.MemberLoginDto;
import com.comatching.common.dto.auth.SocialLoginRequestDto;

public interface MemberService {

	MemberLoginDto socialLogin(SocialLoginRequestDto request);

	MemberLoginDto getMemberById(Long memberId);

	MemberLoginDto getMemberByEmail(String email);
}
