package com.comatching.user.domain.admin.service;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.user.domain.admin.dto.AdminInventoryCounts;
import com.comatching.user.domain.admin.dto.AdminUserDetailResponse;
import com.comatching.user.domain.admin.dto.AdminUserSummaryResponse;
import com.comatching.user.global.exception.UserErrorCode;
import com.comatching.user.infra.client.ItemAdminClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.dto.member.AdminUserProfileDto;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.repository.MemberRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminMemberServiceImpl implements AdminMemberService {

	private final MemberRepository memberRepository;
	private final ItemAdminClient itemAdminClient;

	@Override
	public PagingResponse<AdminUserSummaryResponse> getUsers(String keyword, Pageable pageable) {
		String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;

		Page<AdminUserProfileDto> userPage = memberRepository.searchMembersForAdmin(
				MemberStatus.ACTIVE,
				MemberRole.ROLE_USER,
				normalizedKeyword,
				pageable
			)
			.map(this::toAdminUserProfileDto);

		List<AdminUserProfileDto> users = userPage.getContent();
		Map<Long, AdminInventoryCounts> inventoryCountsByMemberId = itemAdminClient.getInventoryCounts(
				users.stream()
						.map(AdminUserProfileDto::id)
						.toList()
		);

		List<AdminUserSummaryResponse> summaries = users.stream()
				.map(user -> AdminUserSummaryResponse.from(
						user,
						inventoryCountsByMemberId.getOrDefault(user.id(), AdminInventoryCounts.empty())
				))
				.toList();


		return new PagingResponse<>(
				summaries,
				userPage.getNumber(),
				userPage.getSize(),
				userPage.getTotalElements(),
				userPage.getTotalPages(),
				userPage.hasNext(),
				userPage.hasPrevious()
		);
	}

	@Override
	public AdminUserDetailResponse getUserDetail(Long memberId) {
		if (memberId == null || memberId <= 0) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "memberId는 1 이상의 값이어야 합니다.");
		}

		Member member = memberRepository.findAdminMemberById(memberId, MemberStatus.ACTIVE, MemberRole.ROLE_USER)
			.orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_EXIST));
		AdminUserProfileDto user = toAdminUserProfileDto(member);

		Map<Long, AdminInventoryCounts> inventoryCountsByMemberId = itemAdminClient.getInventoryCounts(List.of(memberId));
		AdminInventoryCounts inventoryCounts = inventoryCountsByMemberId.getOrDefault(memberId, AdminInventoryCounts.empty());

		return AdminUserDetailResponse.from(user, inventoryCounts);
	}

	@Override
	@Transactional
	public void updateUserInventory(Long adminId, Long memberId, Object request) {
		// TODO(refactor/admin): getUserDetail 선조회 -> ItemAdminClient.adjustInventory(memberId, adminId, request) 호출
		throw new UnsupportedOperationException("not implemented yet");
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
