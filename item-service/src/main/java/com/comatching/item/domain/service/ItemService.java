package com.comatching.item.domain.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.comatching.common.dto.item.AddItemRequest;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.dto.ItemResponse;

public interface ItemService {

	void useItem(Long memberId, ItemType itemType, int count);

	void addItem(Long memberId, AddItemRequest request);

	Page<ItemResponse> getMyItems(Long memberId, ItemType itemType, Pageable pageable);
}
