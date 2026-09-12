package com.comatching.user.infra.controller;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.dto.member.AdminGiftCardUserProfileDto;
import com.comatching.common.dto.member.AdminUserProfileDto;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.user.domain.member.service.AdminMemberQueryService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/internal/admin/users")
@RequiredArgsConstructor
public class InternalAdminUserController {

	private final AdminMemberQueryService adminMemberQueryService;

	@GetMapping
	public PagingResponse<AdminUserProfileDto> getUsers(
		@RequestParam(required = false) String keyword,
		@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable
	) {
		return adminMemberQueryService.getUsers(keyword, pageable);
	}

//	@GetMapping("/{memberId}")
//	public AdminUserProfileDto getUserDetail(@PathVariable Long memberId) {
//		return adminMemberQueryService.getUserDetail(memberId);
//	}

	@PostMapping("/bulk")
	public List<AdminGiftCardUserProfileDto> getUsersByIds(@RequestBody List<Long> memberIds) {
		// item-service가 상품권 당첨자 명단을 만들 때 회원별 단건 조회를 반복하지 않도록 제공한다.
		return adminMemberQueryService.getUsersByIds(memberIds);
	}
}
