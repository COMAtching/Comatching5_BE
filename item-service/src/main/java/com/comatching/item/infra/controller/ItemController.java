package com.comatching.item.infra.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.dto.item.AddItemRequest;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.item.domain.dto.ItemHistoryResponse;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.dto.ItemResponse;
import com.comatching.item.domain.enums.ItemHistoryType;
import com.comatching.item.domain.service.ItemHistoryService;
import com.comatching.item.domain.service.ItemService;

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

	@PostMapping("/items/add")
	public ResponseEntity<ApiResponse<Void>> addItem(
		@RequestHeader("X-Member-Id") Long memberId,
		@RequestBody AddItemRequest request) {

		itemService.addItem(memberId, request);

		return ResponseEntity.ok(ApiResponse.ok());
	}

	@GetMapping("/items")
	public ResponseEntity<ApiResponse<PagingResponse<ItemResponse>>> getMyItems(
		@RequestHeader("X-Member-Id") Long memberId,
		@RequestParam(required = false) ItemType type,
		@PageableDefault(size = 10, sort = "expiredAt", direction = Sort.Direction.ASC) Pageable pageable
	) {
		PagingResponse<ItemResponse> result = itemService.getMyItems(memberId, type, pageable);
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