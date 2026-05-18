package com.comatching.item.domain.admin.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.comatching.common.annotation.DistributedLock;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.item.domain.admin.dto.AdminInventoryAction;
import com.comatching.item.domain.admin.dto.AdminInventoryUpdateRequest;
import com.comatching.item.domain.item.entity.Item;
import com.comatching.item.domain.item.enums.ItemHistoryType;
import com.comatching.item.domain.item.repository.ItemRepository;
import com.comatching.item.domain.item.service.ItemHistoryService;
import com.comatching.item.global.exception.ItemErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminInventoryAdjustmentService {

	private static final LocalDateTime NO_EXPIRATION = LocalDateTime.of(2099, 12, 31, 23, 59, 59);
	private static final int MAX_REASON_LENGTH = 255;

	private final ItemRepository itemRepository;
	private final ItemHistoryService historyService;

	@DistributedLock(key = "item:inventory", identifier = "#memberId + ':' + #request.itemType()", leaseTime = 10L)
	public void adjust(Long adminId, Long memberId, AdminInventoryUpdateRequest request) {
		validateRequest(request);

		if (request.action() == AdminInventoryAction.ADD) {
			addInventory(memberId, request);
			saveAdminHistory(adminId, memberId, request, request.quantity());
			return;
		}

		removeInventory(memberId, request);
		saveAdminHistory(adminId, memberId, request, -request.quantity());
	}

	private void addInventory(Long memberId, AdminInventoryUpdateRequest request) {
		Item item = Item.builder()
			.memberId(memberId)
			.itemType(request.itemType())
			.quantity(request.quantity())
			.expiredAt(NO_EXPIRATION)
			.build();

		itemRepository.save(item);
	}

	private void removeInventory(Long memberId, AdminInventoryUpdateRequest request) {
		List<Item> items = itemRepository.findAllUsableItems(memberId, request.itemType());

		int totalQuantity = items.stream().mapToInt(Item::getQuantity).sum();
		if (totalQuantity < request.quantity()) {
			throw new BusinessException(ItemErrorCode.NOT_ENOUGH_ITEM);
		}

		int remainingQuantity = request.quantity();
		for (Item item : items) {
			if (remainingQuantity <= 0) {
				break;
			}

			int decreaseQuantity = Math.min(item.getQuantity(), remainingQuantity);
			item.decrease(decreaseQuantity);
			remainingQuantity -= decreaseQuantity;
		}
	}

	private void saveAdminHistory(Long adminId, Long memberId, AdminInventoryUpdateRequest request, int quantity) {
		historyService.saveHistory(
			memberId,
			request.itemType(),
			ItemHistoryType.ADMIN_ADJUSTMENT,
			quantity,
			"관리자 조정(adminId=" + adminId + "): " + request.reason()
		);
	}

	private void validateRequest(AdminInventoryUpdateRequest request) {
		if (request == null || request.itemType() == null || request.action() == null || request.quantity() <= 0
			|| !StringUtils.hasText(request.reason()) || request.reason().length() > MAX_REASON_LENGTH) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE);
		}
	}
}
