package com.comatching.member.domain.service.member.internal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.dto.auth.MemberCreateRequest;
import com.comatching.common.dto.auth.MemberLoginDto;
import com.comatching.common.dto.auth.SocialLoginRequestDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.member.domain.entity.Member;
import com.comatching.member.domain.repository.MemberRepository;
import com.comatching.member.global.exception.MemberErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InternalMemberServiceImpl implements InternalMemberService {

	private final MemberRepository memberRepository;

	@Override
	@Transactional
	public MemberLoginDto socialLogin(SocialLoginRequestDto request) {

		Member member = memberRepository.findBySocialTypeAndSocialId(request.provider(), request.providerId())
			.orElseGet(() -> registerSocialMember(request));

		return toLoginDto(member);
	}

	@Override
	@Transactional
	public void createMember(MemberCreateRequest request) {

		if (memberRepository.existsByEmail(request.email())) {
			throw new BusinessException(MemberErrorCode.DUPLICATE_EMAIL);
		}

		registerMember(request);
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

	@Override
	@Transactional
	public void updatePassword(String email, String encryptedPassword) {
		Member member = memberRepository.findByEmail(email)
			.orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_EXIST));

		member.changePassword(encryptedPassword);
	}

	private Member registerSocialMember(SocialLoginRequestDto request) {
		Member newMember = Member.builder()
			.email(request.email())
			.password(null)
			.socialType(request.provider())
			.socialId(request.providerId())
			.role(MemberRole.ROLE_GUEST)
			.status(MemberStatus.ACTIVE)
			.build();

		return memberRepository.save(newMember);
	}

	private void registerMember(MemberCreateRequest request) {
		Member member = Member.builder()
			.email(request.email())
			.password(request.password())
			.role(MemberRole.ROLE_GUEST)
			.status(MemberStatus.ACTIVE)
			.socialType(null)
			.socialId(null)
			.build();

		memberRepository.save(member);
	}

	private MemberLoginDto toLoginDto(Member member) {
		return MemberLoginDto.builder()
			.id(member.getId())
			.email(member.getEmail())
			.password(member.getPassword())
			.role(member.getRole().name())
			.status(member.getStatus().name())
			.socialType(member.getSocialType())
			.socialId(member.getSocialId())
			.nickname(member.getProfile() != null ? member.getProfile().getNickname() : null)
			.build();
	}
}
