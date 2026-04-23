package com.comatching.item.domain.admin.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.domain.enums.ItemRoute;
import com.comatching.common.dto.item.AddItemRequest;
import com.comatching.common.dto.member.AdminUserProfileDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.item.domain.admin.dto.AdminInventoryAction;
import com.comatching.item.domain.admin.dto.AdminInventoryUpdateRequest;
import com.comatching.item.domain.admin.dto.AdminUserDetailResponse;
import com.comatching.item.domain.admin.dto.AdminUserSummaryResponse;
import com.comatching.item.domain.item.dto.ItemResponse;
import com.comatching.item.domain.item.repository.ItemRepository;
import com.comatching.item.domain.item.service.ItemService;
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
	private final ItemService itemService;

	@Override
	@Transactional(readOnly = true)
	public List<AdminUserSummaryResponse> getUsers(String keyword) {
		try {
			return userAdminClient.getUsers(keyword).stream()
			.map(AdminUserSummaryResponse::from)
			.toList();
		} catch (FeignException e) {
			throw new BusinessException(ItemErrorCode.USER_QUERY_FAILED);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public AdminUserDetailResponse getUserDetail(Long memberId) {
		AdminUserProfileDto user = getUserOrThrow(memberId);
		List<ItemResponse> items = itemRepository.findAllUsableItemsForAdmin(memberId).stream()
			.map(ItemResponse::from)
			.toList();

		return new AdminUserDetailResponse(
			user.id(),
			user.email(),
			user.nickname(),
			user.gender(),
			user.profileImageUrl(),
			items
		);
	}

	@Override
	public void updateUserInventory(Long memberId, AdminInventoryUpdateRequest request) {
		// 존재하지 않는 대상 사용자의 인벤토리 수정 요청을 막기 위해 선조회
		getUserOrThrow(memberId);

		if (request.action() == AdminInventoryAction.ADD) {
			AddItemRequest addItemRequest = new AddItemRequest(
				request.itemType(),
				request.quantity(),
				ItemRoute.CHARGE,
				0
			);
			itemService.addItem(memberId, addItemRequest);
			return;
		}

		itemService.useItem(memberId, request.itemType(), request.quantity());
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
