package com.comatching.item.infra.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.item.domain.admin.dto.AdminInventoryCounts;
import com.comatching.item.domain.admin.dto.AdminInventoryUpdateRequest;
import com.comatching.item.domain.admin.service.AdminItemCommandService;
import com.comatching.item.domain.admin.service.AdminItemQueryService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/internal/admin/items")
@RequiredArgsConstructor
public class InternalAdminItemController {
	private final AdminItemQueryService adminItemQueryService;
	private final AdminItemCommandService adminItemCommandService;

	@GetMapping
	public ResponseEntity<Map<Long, AdminInventoryCounts>> getInventoryCounts(
		@RequestParam("memberIds") List<Long> memberIds
	) {
		return ResponseEntity.ok(adminItemQueryService.getInventoryCounts(memberIds));
	}

	@PatchMapping("/{memberId}")
	public ResponseEntity<Void> adjustInventory(
		@PathVariable Long memberId,
		@RequestHeader("X-Admin-Id") Long adminId,
		@RequestBody AdminInventoryUpdateRequest request
	) {
		adminItemCommandService.adjustInventory(adminId, memberId, request);
		return ResponseEntity.ok().build();
	}
}
