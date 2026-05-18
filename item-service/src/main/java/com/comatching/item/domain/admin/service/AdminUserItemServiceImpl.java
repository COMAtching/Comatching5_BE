package com.comatching.item.domain.admin.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.member.AdminUserProfileDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.item.domain.admin.dto.AdminInventoryCounts;
import com.comatching.item.domain.admin.dto.AdminInventoryUpdateRequest;
import com.comatching.item.domain.admin.dto.AdminUserDetailResponse;
import com.comatching.item.domain.admin.dto.AdminUserSummaryResponse;
import com.comatching.item.domain.item.repository.ItemRepository;
import com.comatching.item.global.exception.ItemErrorCode;
import com.comatching.item.infra.client.UserAdminClient;

import feign.FeignException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminUserItemServiceImpl implements AdminUserItemService {

	private final UserAdminClient userAdminClient;
	private final ItemRepository itemRepository;
	private final AdminInventoryDedupeService adminInventoryDedupeService;
	private final AdminInventoryAdjustmentService adminInventoryAdjustmentService;

	@Override
	@Transactional(readOnly = true)
	public List<AdminUserSummaryResponse> getUsers(String keyword) {
		try {
			List<AdminUserProfileDto> users = userAdminClient.getUsers(keyword);
			Map<Long, AdminInventoryCounts> inventoryCountsByMemberId = getInventoryCountsByMemberId(
				users.stream()
					.map(AdminUserProfileDto::id)
					.toList()
			);

			return users.stream()
				.map(user -> AdminUserSummaryResponse.from(
					user,
					inventoryCountsByMemberId.getOrDefault(user.id(), AdminInventoryCounts.empty())
				))
				.toList();
		} catch (FeignException e) {
			throw new BusinessException(ItemErrorCode.USER_QUERY_FAILED);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public AdminUserDetailResponse getUserDetail(Long memberId) {
		AdminUserProfileDto user = getUserOrThrow(memberId);
		AdminInventoryCounts inventoryCounts = getInventoryCountsByMemberId(List.of(memberId))
			.getOrDefault(memberId, AdminInventoryCounts.empty());

		return new AdminUserDetailResponse(
			user.id(),
			user.email(),
			user.nickname(),
			user.gender(),
			user.profileImageUrl(),
			inventoryCounts.matchingTicketCount(),
			inventoryCounts.optionTicketCount()
		);
	}

	@Override
	public void updateUserInventory(Long adminId, Long memberId, AdminInventoryUpdateRequest request) {
		// 존재하지 않는 대상 사용자의 인벤토리 수정 요청을 막기 위해 선조회
		getUserOrThrow(memberId);
		adminInventoryDedupeService.reserveOrThrow(memberId, request);
		adminInventoryAdjustmentService.adjust(adminId, memberId, request);
	}

	private Map<Long, AdminInventoryCounts> getInventoryCountsByMemberId(List<Long> memberIds) {
		if (memberIds.isEmpty()) {
			return Map.of();
		}

		Map<Long, long[]> countsByMemberId = new HashMap<>();
		for (ItemRepository.MemberItemQuantity quantity : itemRepository.sumUsableQuantityByMemberIds(memberIds)) {
			long[] counts = countsByMemberId.computeIfAbsent(quantity.getMemberId(), ignored -> new long[2]);
			if (quantity.getItemType() == ItemType.MATCHING_TICKET) {
				counts[0] = quantity.getQuantity();
			}
			if (quantity.getItemType() == ItemType.OPTION_TICKET) {
				counts[1] = quantity.getQuantity();
			}
		}

		Map<Long, AdminInventoryCounts> result = new HashMap<>();
		countsByMemberId.forEach((memberId, counts) ->
			result.put(memberId, new AdminInventoryCounts(counts[0], counts[1]))
		);
		return result;
	}

	private AdminUserProfileDto getUserOrThrow(Long memberId) {
		if (memberId == null || memberId <= 0) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "memberId는 1 이상의 값이어야 합니다.");
		}
		try {
			return userAdminClient.getUserDetail(memberId);
		} catch (FeignException e) {
			if (e.status() == 400 || e.status() == 404) {
				throw new BusinessException(ItemErrorCode.TARGET_USER_NOT_FOUND);
			}
			throw new BusinessException(ItemErrorCode.USER_QUERY_FAILED);
		}
	}
}
