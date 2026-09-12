package com.comatching.user.domain.member.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.dto.member.AdminGiftCardUserProfileDto;
import com.comatching.common.dto.member.AdminUserProfileDto;
import com.comatching.common.dto.response.PagingResponse;
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
	public PagingResponse<AdminUserProfileDto> getUsers(String keyword, Pageable pageable) {
		String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;

		Page<AdminUserProfileDto> users = memberRepository.searchMembersForAdmin(
				MemberStatus.ACTIVE,
				MemberRole.ROLE_USER,
				normalizedKeyword,
				pageable
			)
			.map(this::toAdminUserProfileDto);

		return PagingResponse.from(users);
	}

	@Override
	public AdminUserProfileDto getUserDetail(Long memberId) {
		Member member = memberRepository.findAdminMemberById(memberId, MemberStatus.ACTIVE, MemberRole.ROLE_USER)
			.orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_EXIST));

		return toAdminUserProfileDto(member);
	}

	@Override
	public List<AdminGiftCardUserProfileDto> getUsersByIds(List<Long> memberIds) {
		// 미지급 상품권 명단에 표시할 이메일, 실명, 닉네임을 회원 ID 목록으로 한 번에 조회한다.
		// 반환 순서는 보장하지 않으며 호출 서비스가 회원 ID를 기준으로 원래 명단에 결합한다.
		return memberRepository.findAdminMembersByIds(
				memberIds,
				MemberStatus.ACTIVE,
				MemberRole.ROLE_USER
			)
			.stream()
			.map(this::toAdminGiftCardUserProfileDto)
			.toList();
	}

	private AdminGiftCardUserProfileDto toAdminGiftCardUserProfileDto(Member member) {
		return new AdminGiftCardUserProfileDto(
			member.getId(),
			member.getEmail(),
			member.getRealName(),
			member.getProfile().getNickname()
		);
	}

	private AdminUserProfileDto toAdminUserProfileDto(Member member) {
		return new AdminUserProfileDto(
			member.getId(),
			member.getEmail(),
			member.getRealName(),
			member.getProfile().getNickname(),
			member.getProfile().getGender(),
			member.getProfile().getProfileImageUrl()
		);
	}
}
