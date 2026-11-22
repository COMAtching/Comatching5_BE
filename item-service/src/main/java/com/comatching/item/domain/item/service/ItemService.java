package com.comatching.item.domain.item.service;

import org.springframework.data.domain.Pageable;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.item.AddItemRequest;
import com.comatching.item.domain.item.dto.MyItemsResponse;

public interface ItemService {

	void useItem(Long memberId, ItemType itemType, int count);

	void addItem(Long memberId, AddItemRequest request);

	// 호출자가 회원·품목 재고 락과 DB 트랜잭션을 확보한 상태에서 사용한다.
	void addUnderInventoryLock(Long memberId, AddItemRequest request);

	MyItemsResponse getMyItems(Long memberId, ItemType itemType, Pageable pageable);
}
