package com.comatching.member.domain.service.member.internal;

import com.comatching.common.dto.auth.MemberCreateRequest;
import com.comatching.common.dto.auth.MemberLoginDto;
import com.comatching.common.dto.auth.SocialLoginRequestDto;

public interface InternalMemberService {

	MemberLoginDto socialLogin(SocialLoginRequestDto request);

	void createMember(MemberCreateRequest request);

	MemberLoginDto getMemberById(Long memberId);

	MemberLoginDto getMemberByEmail(String email);

	void updatePassword(String email, String encryptedPassword);
}
