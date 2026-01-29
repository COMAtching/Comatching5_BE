package com.comatching.matching.infra.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.item.AddItemRequest;

@FeignClient(name = "item-service", url = "${item-service.url}")
public interface ItemClient {

	@PostMapping("/api/internal/items/use")
	void useItem(
		@RequestHeader("X-Member-Id") Long memberId,
		@RequestParam("itemType") ItemType itemType,
		@RequestParam("count") int count
	);

	@PostMapping("/api/items/add")
	void addItem(
		@RequestHeader("X-Member-Id") Long memberId,
		@RequestBody AddItemRequest request
	);
}
