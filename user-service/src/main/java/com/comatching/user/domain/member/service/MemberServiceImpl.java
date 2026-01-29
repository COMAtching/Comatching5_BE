package com.comatching.user.domain.member.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.dto.auth.MemberLoginDto;
import com.comatching.common.dto.auth.SocialLoginRequestDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.user.domain.event.UserEventPublisher;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.repository.MemberRepository;
import com.comatching.user.global.exception.UserErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberServiceImpl implements MemberService {

	private final MemberRepository memberRepository;
	private final UserEventPublisher eventPublisher;

	@Override
	@Transactional
	public MemberLoginDto socialLogin(SocialLoginRequestDto request) {

		Member member = memberRepository.findBySocialTypeAndSocialId(request.provider(), request.providerId())
			.orElseGet(() -> registerSocialMember(request));

		return toLoginDto(member);
	}

	@Override
	@Transactional
	public void createMember(String email, String encodedPassword) {

		if (memberRepository.existsByEmail(email)) {
			throw new BusinessException(UserErrorCode.DUPLICATE_EMAIL);
		}

		registerMember(email, encodedPassword);
	}

	@Override
	public Member getMemberById(Long memberId) {

		return memberRepository.findById(memberId)
			.orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_EXIST));
	}

	@Override
	public Member getMemberByEmail(String email) {
		return memberRepository.findByEmail(email)
			.orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_EXIST));
	}

	@Override
	@Transactional
	public void updatePassword(String email, String encryptedPassword) {
		Member member = memberRepository.findByEmail(email)
			.orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_EXIST));

		member.changePassword(encryptedPassword);
	}

	@Override
	@Transactional
	public void withdrawMember(Long memberId) {
		Member member = memberRepository.findById(memberId)
			.orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_EXIST));

		String email = member.getEmail();
		member.withdraw();

		// Kafka 이벤트 발행
		eventPublisher.sendWithdrawEvent(memberId, email);
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

	private void registerMember(String email, String encodedPassword) {
		Member member = Member.builder()
			.email(email)
			.password(encodedPassword)
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
