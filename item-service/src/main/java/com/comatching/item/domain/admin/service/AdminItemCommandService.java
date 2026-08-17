package com.comatching.item.domain.admin.service;

import org.springframework.stereotype.Service;

import com.comatching.item.domain.admin.dto.AdminInventoryUpdateRequest;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminItemCommandService {

	private final AdminInventoryDedupeService adminInventoryDedupeService;
	private final AdminInventoryAdjustmentService adminInventoryAdjustmentService;

	public void adjustInventory(Long adminId, Long memberId, AdminInventoryUpdateRequest request) {
		adminInventoryDedupeService.reserveOrThrow(memberId, request);
		adminInventoryAdjustmentService.adjust(adminId, memberId, request);
	}
}
