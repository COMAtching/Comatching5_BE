package com.comatching.user.domain.member.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.dto.member.AdminUserProfileDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.repository.MemberRepository;
import com.comatching.user.global.exception.UserErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminMemberQueryServiceImpl implements AdminMemberQueryService {

	private final MemberRepository memberRepository;

	@Override
	public List<AdminUserProfileDto> getUsers(String keyword) {
		String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;

		return memberRepository.searchMembersForAdmin(
				MemberStatus.ACTIVE,
				MemberRole.ROLE_USER,
				normalizedKeyword
			).stream()
			.map(this::toAdminUserProfileDto)
			.toList();
	}

	@Override
	public AdminUserProfileDto getUserDetail(Long memberId) {
		Member member = memberRepository.findAdminMemberById(memberId, MemberStatus.ACTIVE, MemberRole.ROLE_USER)
			.orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_EXIST));

		return toAdminUserProfileDto(member);
	}

	private AdminUserProfileDto toAdminUserProfileDto(Member member) {
		return new AdminUserProfileDto(
			member.getId(),
			member.getEmail(),
			member.getProfile().getNickname(),
			member.getProfile().getGender(),
			member.getProfile().getProfileImageUrl()
		);
	}
}
