package com.comatching.item.domain.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.item.domain.dto.ItemHistoryResponse;
import com.comatching.item.domain.entity.ItemHistory;
import com.comatching.item.domain.enums.ItemHistoryType;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.repository.ItemHistoryRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class ItemHistoryServiceImpl implements ItemHistoryService{

	private final ItemHistoryRepository historyRepository;

	@Override
	public void saveHistory(Long memberId, ItemType itemType, ItemHistoryType historyType, int quantity, String description) {
		ItemHistory history = ItemHistory.builder()
			.memberId(memberId)
			.itemType(itemType)
			.historyType(historyType)
			.quantity(quantity)
			.description(description)
			.build();

		historyRepository.save(history);
	}

	@Override
	@Transactional(readOnly = true)
	public Page<ItemHistoryResponse> getMyHistory(Long memberId, Pageable pageable) {
		return historyRepository.findByMemberIdOrderByCreatedAtDesc(memberId, pageable)
			.map(ItemHistoryResponse::from);
	}

	@Override
	public Page<ItemHistoryResponse> searchMyHistory(Long memberId, ItemType itemType, ItemHistoryType historyType,
		Pageable pageable) {
		return historyRepository.searchHistory(memberId, itemType, historyType, pageable)
			.map(ItemHistoryResponse::from);
	}
}
