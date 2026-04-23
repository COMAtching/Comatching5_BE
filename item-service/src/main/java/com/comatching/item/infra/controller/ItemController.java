package com.comatching.item.infra.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.comatching.common.dto.item.AddItemRequest;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.item.domain.item.dto.ItemHistoryResponse;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.item.dto.MyItemsResponse;
import com.comatching.item.domain.item.enums.ItemHistoryType;
import com.comatching.item.domain.item.service.ItemHistoryService;
import com.comatching.item.domain.item.service.ItemService;
import com.comatching.common.dto.response.PagingResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ItemController {

	private final ItemService itemService;
	private final ItemHistoryService itemHistoryService;

	@PostMapping("/internal/items/use")
	public void useItem(
		@RequestHeader("X-Member-Id") Long memberId,
		@RequestParam ItemType itemType,
		@RequestParam int count) {

		itemService.useItem(memberId, itemType, count);
	}

	@PostMapping("/internal/items/add")
	public void addItem(
		@RequestHeader("X-Member-Id") Long memberId,
		@RequestBody AddItemRequest request
	) {
		itemService.addItem(memberId, request);
	}

	@GetMapping("/items")
	public ResponseEntity<ApiResponse<MyItemsResponse>> getMyItems(
		@RequestHeader("X-Member-Id") Long memberId,
		@RequestParam(required = false) ItemType type,
		@PageableDefault(size = 10, sort = "expiredAt", direction = Sort.Direction.ASC) Pageable pageable
	) {
		MyItemsResponse result = itemService.getMyItems(memberId, type, pageable);
		return ResponseEntity.ok(ApiResponse.ok(result));
	}

	@GetMapping("/items/history")
	public ResponseEntity<ApiResponse<PagingResponse<ItemHistoryResponse>>> getMyHistory(
		@RequestHeader("X-Member-Id") Long memberId,
		@RequestParam(required = false) ItemType type,
		@RequestParam(required = false) ItemHistoryType historyType,
		@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
	) {
		PagingResponse<ItemHistoryResponse> result = itemHistoryService.searchMyHistory(memberId, type, historyType, pageable);
		return ResponseEntity.ok(ApiResponse.ok(result));
	}
}
