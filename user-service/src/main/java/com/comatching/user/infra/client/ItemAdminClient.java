package com.comatching.user.infra.client;

import java.util.List;
import java.util.Map;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import com.comatching.user.domain.admin.dto.AdminInventoryCounts;
import com.comatching.user.domain.admin.dto.AdminInventoryUpdateRequest;

@FeignClient(name = "item-service-admin", url = "${item-service.url}", path = "/api/internal/admin/items")
public interface ItemAdminClient {

	@GetMapping
	Map<Long, AdminInventoryCounts> getInventoryCounts(@RequestParam("memberIds") List<Long> memberIds);

	@PatchMapping("/{memberId}")
	void adjustInventory(
			@PathVariable Long memberId,
			@RequestHeader("X-Admin-Id") Long adminId,
			@RequestBody AdminInventoryUpdateRequest request
	);
}
