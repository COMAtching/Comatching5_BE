//package com.comatching.user.infra.controller;
//
//import org.springframework.data.domain.Pageable;
//import org.springframework.data.domain.Sort;
//import org.springframework.data.web.PageableDefault;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.PathVariable;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RequestParam;
//import org.springframework.web.bind.annotation.RestController;
//
//import com.comatching.common.dto.member.AdminUserProfileDto;
//import com.comatching.common.dto.response.PagingResponse;
//import com.comatching.user.domain.member.service.AdminMemberQueryService;
//
//import lombok.RequiredArgsConstructor;
//
//@RestController
//@RequestMapping("/api/internal/admin/users")
//@RequiredArgsConstructor
//public class InternalAdminUserController {
//
//	private final AdminMemberQueryService adminMemberQueryService;
//
//	@GetMapping
//	public PagingResponse<AdminUserProfileDto> getUsers(
//		@RequestParam(required = false) String keyword,
//		@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable
//	) {
//		return adminMemberQueryService.getUsers(keyword, pageable);
//	}
//
//	@GetMapping("/{memberId}")
//	public AdminUserProfileDto getUserDetail(@PathVariable Long memberId) {
//		return adminMemberQueryService.getUserDetail(memberId);
//	}
//}
