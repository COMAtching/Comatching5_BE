package com.comatching.user.infra.controller;

import com.comatching.common.dto.response.PagingResponse;
import com.comatching.user.domain.admin.dto.AdminUserDetailResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.user.domain.admin.dto.AdminUserSummaryResponse;
import com.comatching.user.domain.admin.service.AdminMemberService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;


@Tag(name = "Admin User API", description = "관리자 전용 사용자 조회 및 인벤토리 관리 (현재 미구현)")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminMemberController {

	private final AdminMemberService adminMemberService;

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(summary = "사용자 목록 조회/검색", description = "관리자가 이메일/닉네임/이름 키워드로 사용자 목록을 페이징 조회합니다.")
	@GetMapping
	public ResponseEntity<ApiResponse<PagingResponse<AdminUserSummaryResponse>>> getUsers(
		@CurrentMember MemberInfo memberInfo,
		@RequestParam(required = false) String keyword,
		@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable
	) {
		return ResponseEntity.ok(ApiResponse.ok(adminMemberService.getUsers(keyword, pageable)));
	}

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(summary = "사용자 상세 조회", description = "관리자가 사용자 상세 정보와 보유 아이템 인벤토리를 조회합니다.")
	@GetMapping("/{memberId}")
	public ResponseEntity<ApiResponse<AdminUserDetailResponse>> getUserDetail(
		@CurrentMember MemberInfo memberInfo,
		@PathVariable Long memberId
	) {
		return ResponseEntity.ok(ApiResponse.ok(adminMemberService.getUserDetail(memberId)));
	}

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(summary = "사용자 아이템 인벤토리 수정", description = "관리자가 특정 사용자 인벤토리에 아이템을 추가하거나 차감합니다.")
	@PatchMapping("/{memberId}/items")
	public ResponseEntity<ApiResponse<Void>> updateUserInventory(
		@CurrentMember MemberInfo memberInfo,
		@PathVariable Long memberId,
		@RequestBody Object request
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}
}
