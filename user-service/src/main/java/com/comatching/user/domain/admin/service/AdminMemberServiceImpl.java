package com.comatching.user.domain.admin.service;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.dto.member.AdminUserProfileDto;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.user.domain.admin.dto.AdminInventoryCounts;
import com.comatching.user.domain.admin.dto.AdminInventoryUpdateRequest;
import com.comatching.user.domain.admin.dto.AdminUserDetailResponse;
import com.comatching.user.domain.admin.dto.AdminUserSummaryResponse;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.repository.MemberRepository;
import com.comatching.user.global.exception.UserErrorCode;
import com.comatching.user.infra.client.ItemAdminClient;

import feign.FeignException;
import feign.codec.DecodeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
			.orElseThrow(() -> new BusinessException(UserErrorCode.TARGET_USER_NOT_FOUND));
		AdminUserProfileDto user = toAdminUserProfileDto(member);

		Map<Long, AdminInventoryCounts> inventoryCountsByMemberId = itemAdminClient.getInventoryCounts(List.of(memberId));
		AdminInventoryCounts inventoryCounts = inventoryCountsByMemberId.getOrDefault(memberId, AdminInventoryCounts.empty());

		return AdminUserDetailResponse.from(user, inventoryCounts);
	}

	@Override
	@Transactional
	public void updateUserInventory(Long adminId, Long memberId, AdminInventoryUpdateRequest request) {
		// 존재하지 않는 대상 사용자의 인벤토리 수정 요청을 막기 위해 선조회
		memberRepository.findAdminMemberById(memberId, MemberStatus.ACTIVE, MemberRole.ROLE_USER)
			.orElseThrow(() -> new BusinessException(UserErrorCode.TARGET_USER_NOT_FOUND));

		try {
			itemAdminClient.adjustInventory(memberId, adminId, request);
		} catch (DecodeException e) {
			// item-service 응답 본문을 역직렬화하지 못한 경우(스펙 불일치 등) - 원인 파악이 불가능하므로 조회 실패로 처리
			log.warn("Admin inventory adjustment decode failed. memberId={}", memberId, e);
			throw new BusinessException(UserErrorCode.USER_QUERY_FAILED);
		} catch (FeignException e) {
			if (e.status() == 409) {
				// AdminInventoryDedupeService가 3초 내 동일 요청(memberId+itemType+action+quantity+reason)을 감지 - 재시도/중복클릭으로 재요청하면 안 됨
				throw new BusinessException(UserErrorCode.DUPLICATE_ADMIN_INVENTORY_ADJUSTMENT);
			}
			if (e.status() == 400) {
				// AdminInventoryAdjustmentService가 REMOVE 처리 중 보유 수량 부족을 감지 - 수량을 줄이거나 회원 보유량을 먼저 확인해야 함
				throw new BusinessException(UserErrorCode.NOT_ENOUGH_ITEM);
			}
			// 그 외(5xx, 타임아웃 등)는 item-service 쪽 원인을 특정할 수 없는 조정 실패 - 로그로 남기고 재시도 여부는 호출측 판단에 맡김
			log.warn("Admin inventory adjustment failed. memberId={}, status={}, body={}",
				memberId, e.status(), e.contentUTF8(), e);
			throw new BusinessException(UserErrorCode.USER_QUERY_FAILED);
		}
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
