package com.comatching.item.infra.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.item.domain.product.dto.PurchaseRequestDto;
import com.comatching.item.domain.product.service.AdminPaymentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Admin Payment API", description = "관리자 전용 결제 승인 및 관리")
@RestController
@RequestMapping("/api/v1/admin/payment")
@RequiredArgsConstructor
public class AdminPaymentController {

	private final AdminPaymentService adminPaymentService;

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(summary = "승인 대기 목록 조회", description = "아직 처리되지 않은(PENDING) 구매 요청 목록을 페이지 단위로 최신순 조회합니다.")
	@GetMapping("/requests")
	public ResponseEntity<ApiResponse<PagingResponse<PurchaseRequestDto>>> getPendingRequests(
		@CurrentMember MemberInfo memberInfo,
		@PageableDefault(size = 20, sort = "requestedAt", direction = Sort.Direction.DESC) Pageable pageable
	) {
		return ResponseEntity.ok(ApiResponse.ok(adminPaymentService.getPendingRequests(pageable)));
	}

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(summary = "구매 승인 및 아이템 지급", description = "입금이 확인된 요청 건을 승인하고 사용자에게 아이템을 지급합니다.")
	@PostMapping("/approve/{requestId}")
	public ResponseEntity<ApiResponse<Void>> approvePurchase(@PathVariable Long requestId, @CurrentMember MemberInfo memberInfo) {
		adminPaymentService.approvePurchase(requestId, memberInfo.memberId());
		return ResponseEntity.ok(ApiResponse.ok());
	}

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(summary = "구매 요청 거부", description = "관리자가 대기 중(PENDING) 구매 요청 건을 거부 처리합니다.")
	@PostMapping("/reject/{requestId}")
	public ResponseEntity<ApiResponse<Void>> rejectPurchase(@PathVariable Long requestId, @CurrentMember MemberInfo memberInfo) {
		adminPaymentService.rejectPurchase(requestId, memberInfo.memberId());
		return ResponseEntity.ok(ApiResponse.ok());
	}
}
