package com.comatching.item.domain.item.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.domain.enums.ItemRoute;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.item.AddItemRequest;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.item.domain.item.dto.ItemResponse;
import com.comatching.item.domain.item.dto.MyItemsResponse;
import com.comatching.item.domain.item.entity.Item;
import com.comatching.item.domain.item.enums.ItemHistoryType;
import com.comatching.item.domain.item.repository.ItemRepository;
import com.comatching.item.global.exception.ItemErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class ItemServiceImpl implements ItemService {

	private final ItemRepository itemRepository;
	private final ItemHistoryService historyService;

	@Override
	public void useItem(Long memberId, ItemType itemType, int count) {
		if (count <= 0) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "차감 수량은 1 이상이어야 합니다.");
		}

		List<Item> items = itemRepository.findAllUsableItems(memberId, itemType);

		int totalQuantity = items.stream().mapToInt(Item::getQuantity).sum();
		if (totalQuantity < count) {
			throw new BusinessException(ItemErrorCode.NOT_ENOUGH_ITEM);
		}

		int remainingCount = count;
		for (Item item : items) {
			if (remainingCount <= 0)
				break;

			if (item.getQuantity() >= remainingCount) {
				item.decrease(remainingCount);
				remainingCount = 0;
			} else {
				int available = item.getQuantity();
				item.decrease(available);
				remainingCount -= available;
			}
		}

		historyService.saveHistory(
			memberId,
			itemType,
			ItemHistoryType.USE,
			-count,
			itemType.getDescription() + " 사용"
		);
	}

	@Override
	public void addItem(Long memberId, AddItemRequest request) {
		if (request.quantity() <= 0) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "지급 수량은 1 이상이어야 합니다.");
		}

		LocalDateTime expiredAt =
			request.route().equals(ItemRoute.EVENT) ? LocalDateTime.now().plusDays(request.expiredAt()) :
				LocalDateTime.of(2099, 12, 31, 23, 59, 59);

		Item item = Item.builder()
			.memberId(memberId)
			.itemType(request.itemType())
			.quantity(request.quantity())
			.expiredAt(expiredAt)
			.build();

		itemRepository.save(item);

		ItemHistoryType historyType = switch (request.route()) {
			case CHARGE -> ItemHistoryType.CHARGE;
			case EVENT -> ItemHistoryType.EVENT;
			case REFUND -> ItemHistoryType.REFUND;
		};

		historyService.saveHistory(
			memberId,
			request.itemType(),
			historyType,
			request.quantity(),
			request.itemType().getDescription() + " " + request.route().getDescription()
		);

	}

	@Override
	@Transactional(readOnly = true)
	public MyItemsResponse getMyItems(Long memberId, ItemType itemType, Pageable pageable) {
		PagingResponse<ItemResponse> items = PagingResponse.from(
			itemRepository.findMyUsableItems(memberId, itemType, pageable)
				.map(ItemResponse::from)
		);

		long matchingTicketCount = itemRepository.sumUsableQuantityByMemberIdAndItemType(memberId, ItemType.MATCHING_TICKET);
		long optionTicketCount = itemRepository.sumUsableQuantityByMemberIdAndItemType(memberId, ItemType.OPTION_TICKET);

		return new MyItemsResponse(items, matchingTicketCount, optionTicketCount);
	}
}
