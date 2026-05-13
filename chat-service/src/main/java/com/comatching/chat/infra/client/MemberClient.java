package com.comatching.chat.infra.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.comatching.common.dto.member.ProfileResponse;

@FeignClient(name = "user-service", path = "/api/internal/users", url = "${user-service.url}")
public interface MemberClient {

	@PostMapping("/profiles/bulk")
	List<ProfileResponse> getProfiles(@RequestBody List<Long> memberIds);
}
