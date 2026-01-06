package com.comatching.member.domain.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.dto.auth.MemberLoginDto;
import com.comatching.common.dto.auth.SocialLoginRequestDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.member.domain.entity.Member;
import com.comatching.member.domain.repository.MemberRepository;
import com.comatching.member.global.exception.MemberErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberServiceImpl implements MemberService{

	private final MemberRepository memberRepository;

	@Override
	@Transactional
	public MemberLoginDto socialLogin(SocialLoginRequestDto request) {

		Member member = memberRepository.findBySocialTypeAndSocialId(request.provider(), request.providerId())
			.orElseGet(() -> registerSocialMember(request));

		return toLoginDto(member);
	}

	@Override
	public MemberLoginDto getMemberById(Long memberId) {

		Member member = memberRepository.findById(memberId)
			.orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_EXIST));

		return toLoginDto(member);
	}

	@Override
	public MemberLoginDto getMemberByEmail(String email) {
		Member member = memberRepository.findByEmail(email)
			.orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_EXIST));

		return toLoginDto(member);
	}

	private Member registerSocialMember(SocialLoginRequestDto request) {
		Member newMember = Member.builder()
			.email(request.email())
			.socialType(request.provider())
			.socialId(request.providerId())
			.role(MemberRole.ROLE_USER)
			.status(MemberStatus.PENDING)
			.build();

		return memberRepository.save(newMember);
	}

	private MemberLoginDto toLoginDto(Member member) {
		return MemberLoginDto.builder()
			.id(member.getId())
			.email(member.getEmail())
			.password(member.getPassword())
			.role(member.getRole().name())
			.status(member.getStatus().name())
			.build();
	}
}
