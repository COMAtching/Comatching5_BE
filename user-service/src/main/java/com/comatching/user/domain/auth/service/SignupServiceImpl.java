package com.comatching.user.domain.auth.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.auth.LoginSession;
import com.comatching.common.dto.auth.SignupRequest;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.util.JwtUtil;
import com.comatching.user.domain.auth.dto.CompleteSignupResponse;
import com.comatching.user.domain.mail.service.EmailService;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.service.MemberService;
import com.comatching.user.domain.member.service.ProfileCreateService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SignupServiceImpl implements SignupService {
	private final EmailService emailService;
	private final MemberService memberService;
	private final ProfileCreateService profileService;
	private final PasswordEncoder passwordEncoder;
	private final JwtUtil jwt;
	private final LoginSessionService sessions;

	@Override
	public void signup(SignupRequest request, String signupProof) {
		emailService.consumeSignupProof(request.email(), signupProof);
		memberService.createMember(request.email(), passwordEncoder.encode(request.password()));
	}

	@Override
	@Transactional
	public CompletedSignup completeSignup(MemberInfo memberInfo, ProfileCreateRequest request, String accessToken) {
		LoginSession session = jwt.readAccess(accessToken).session();
		if (memberInfo.memberId() != session.memberId()) throw new BusinessException(GeneralErrorCode.UNAUTHORIZED);
		Member member = sessions.lockActiveMember(session);
		ProfileResponse profile = profileService.createProfile(member.getId(), request);
		var result = new CompleteSignupResponse(profile, false);
		return new CompletedSignup(result, sessions.upgrade(member, session, profile.nickname()));
	}
}
