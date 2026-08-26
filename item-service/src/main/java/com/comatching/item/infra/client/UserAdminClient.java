package com.comatching.item.infra.client;

/*
 * Temporarily disabled with the item-service admin user API.
 * This prevents requests to user-service for the migrated endpoints.
 *

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.comatching.common.dto.member.AdminUserProfileDto;
import com.comatching.common.dto.response.PagingResponse;

@FeignClient(name = "user-service-admin", url = "${user-service.url}", path = "/api/internal/admin/users")
public interface UserAdminClient {

	@GetMapping
	PagingResponse<AdminUserProfileDto> getUsers(
		@RequestParam(value = "keyword", required = false) String keyword,
		@RequestParam("page") int page,
		@RequestParam("size") int size,
		@RequestParam(value = "sort", required = false) List<String> sort
	);

	@GetMapping("/{memberId}")
	AdminUserProfileDto getUserDetail(@PathVariable Long memberId);
}
*/
