package com.comatching.auth.infra.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.comatching.common.dto.auth.MemberLoginDto;

// url: 로컬 테스트용 (배포시 제거)
@FeignClient(name = "member-service", url = "${member-service.url:}")
public interface MemberServiceClient {

	// Member 서비스의 엔드포인트와 일치시켜야 함
	@GetMapping("/api/internal/members/login-info")
	MemberLoginDto getMemberByEmail(@RequestParam("email") String email);

	@GetMapping("/api/internal/members/login-info")
	MemberLoginDto getMemberById(@RequestParam("id") Long id);
}
