package com.comatching.member.domain.service.member.external;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.exception.BusinessException;
import com.comatching.member.domain.entity.Member;
import com.comatching.member.domain.repository.MemberRepository;
import com.comatching.member.global.exception.MemberErrorCode;
import com.comatching.member.infra.kafka.MemberEventProducer;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class MemberWithdrawServiceImpl implements MemberWithdrawService {

	private final MemberRepository memberRepository;
	private final MemberEventProducer memberEventProducer;

	@Override
	public void withdrawMember(Long memberId) {

		Member member = memberRepository.findById(memberId)
			.orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_EXIST));

		memberEventProducer.sendWithdrawEvent(memberId, member.getEmail());
		member.withdraw();

	}
}
