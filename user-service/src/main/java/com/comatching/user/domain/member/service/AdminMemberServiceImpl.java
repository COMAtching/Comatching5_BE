package com.comatching.user.domain.member.service;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.dto.member.AdminUserProfileDto;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.user.domain.member.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminMemberServiceImpl implements AdminMemberService {

	private final MemberRepository memberRepository;

	@Override
	public PagingResponse<AdminUserProfileDto> getUsers(String keyword, Pageable pageable) {
		// TODO(refactor/admin): AdminMemberQueryServiceImpl#getUsers 로직 이식 + ItemAdminClient 티켓 수량 합성
		throw new UnsupportedOperationException("not implemented yet");
	}

	@Override
	public AdminUserProfileDto getUserDetail(Long memberId) {
		// TODO(refactor/admin): AdminMemberQueryServiceImpl#getUserDetail 로직 이식 + ItemAdminClient 티켓 수량 합성
		throw new UnsupportedOperationException("not implemented yet");
	}

	@Override
	@Transactional
	public void updateUserInventory(Long adminId, Long memberId, Object request) {
		// TODO(refactor/admin): getUserDetail 선조회 -> ItemAdminClient.adjustInventory(memberId, adminId, request) 호출
		throw new UnsupportedOperationException("not implemented yet");
	}
}
