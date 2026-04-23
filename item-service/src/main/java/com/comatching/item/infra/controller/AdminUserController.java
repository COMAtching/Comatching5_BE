package com.comatching.item.infra.controller;

import java.util.List;

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
import com.comatching.item.domain.admin.dto.AdminInventoryUpdateRequest;
import com.comatching.item.domain.admin.dto.AdminUserDetailResponse;
import com.comatching.item.domain.admin.dto.AdminUserSummaryResponse;
import com.comatching.item.domain.admin.service.AdminUserItemService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Admin User API", description = "관리자 전용 사용자 조회 및 인벤토리 관리")
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

	private final AdminUserItemService adminUserItemService;

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(summary = "사용자 목록 조회/검색", description = "관리자가 이메일/닉네임 키워드로 사용자 목록을 조회합니다.")
	@GetMapping
	public ResponseEntity<ApiResponse<List<AdminUserSummaryResponse>>> getUsers(
		@CurrentMember MemberInfo memberInfo,
		@RequestParam(required = false) String keyword
	) {
		return ResponseEntity.ok(ApiResponse.ok(adminUserItemService.getUsers(keyword)));
	}

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(summary = "사용자 상세 조회", description = "관리자가 사용자 상세 정보와 보유 아이템 인벤토리를 조회합니다.")
	@GetMapping("/{memberId}")
	public ResponseEntity<ApiResponse<AdminUserDetailResponse>> getUserDetail(
		@CurrentMember MemberInfo memberInfo,
		@PathVariable Long memberId
	) {
		return ResponseEntity.ok(ApiResponse.ok(adminUserItemService.getUserDetail(memberId)));
	}

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(summary = "사용자 아이템 인벤토리 수정", description = "관리자가 특정 사용자 인벤토리에 아이템을 추가하거나 차감합니다.")
	@PatchMapping("/{memberId}/items")
	public ResponseEntity<ApiResponse<Void>> updateUserInventory(
		@CurrentMember MemberInfo memberInfo,
		@PathVariable Long memberId,
		@RequestBody @Valid AdminInventoryUpdateRequest request
	) {
		adminUserItemService.updateUserInventory(memberId, request);
		return ResponseEntity.ok(ApiResponse.ok());
	}
}
