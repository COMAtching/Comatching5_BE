package com.comatching.item.domain.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.comatching.common.dto.response.PagingResponse;
import com.comatching.item.domain.dto.ItemHistoryResponse;
import com.comatching.item.domain.enums.ItemHistoryType;
import com.comatching.common.domain.enums.ItemType;

public interface ItemHistoryService {

	void saveHistory(Long memberId, ItemType itemType, ItemHistoryType historyType, int quantity, String description);

	Page<ItemHistoryResponse> getMyHistory(Long memberId, Pageable pageable);

	PagingResponse<ItemHistoryResponse> searchMyHistory(Long memberId, ItemType itemType, ItemHistoryType historyType, Pageable pageable);
}
