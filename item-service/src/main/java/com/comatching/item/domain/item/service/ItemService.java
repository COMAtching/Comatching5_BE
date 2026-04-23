package com.comatching.item.domain.item.service;

import org.springframework.data.domain.Pageable;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.item.AddItemRequest;
import com.comatching.item.domain.item.dto.MyItemsResponse;

public interface ItemService {

	void useItem(Long memberId, ItemType itemType, int count);

	void addItem(Long memberId, AddItemRequest request);

	MyItemsResponse getMyItems(Long memberId, ItemType itemType, Pageable pageable);
}
