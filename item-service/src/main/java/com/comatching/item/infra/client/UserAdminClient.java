package com.comatching.item.infra.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.comatching.common.dto.member.AdminUserProfileDto;

@FeignClient(name = "user-service-admin", url = "${user-service.url}", path = "/api/internal/admin/users")
public interface UserAdminClient {

	@GetMapping
	List<AdminUserProfileDto> getUsers(
		@RequestParam(required = false) String keyword
	);

	@GetMapping("/{memberId}")
	AdminUserProfileDto getUserDetail(@PathVariable Long memberId);
}
