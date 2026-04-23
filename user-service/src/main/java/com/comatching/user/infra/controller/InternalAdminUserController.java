package com.comatching.user.infra.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.dto.member.AdminUserProfileDto;
import com.comatching.user.domain.member.service.AdminMemberQueryService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/internal/admin/users")
@RequiredArgsConstructor
public class InternalAdminUserController {

	private final AdminMemberQueryService adminMemberQueryService;

	@GetMapping
	public List<AdminUserProfileDto> getUsers(
		@RequestParam(required = false) String keyword
	) {
		return adminMemberQueryService.getUsers(keyword);
	}

	@GetMapping("/{memberId}")
	public AdminUserProfileDto getUserDetail(@PathVariable Long memberId) {
		return adminMemberQueryService.getUserDetail(memberId);
	}
}
