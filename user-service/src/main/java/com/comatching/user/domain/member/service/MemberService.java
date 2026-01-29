package com.comatching.user.domain.member.service;

import com.comatching.common.dto.auth.MemberLoginDto;
import com.comatching.common.dto.auth.SocialLoginRequestDto;
import com.comatching.user.domain.member.entity.Member;

public interface MemberService {

	MemberLoginDto socialLogin(SocialLoginRequestDto request);

	void createMember(String email, String encodedPassword);

	Member getMemberById(Long memberId);

	Member getMemberByEmail(String email);

	void updatePassword(String email, String encryptedPassword);

	void withdrawMember(Long memberId);
}
