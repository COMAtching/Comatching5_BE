package com.comatching.item.infra.controller.admin;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.item.domain.order.dto.FailedGrantResponse;
import com.comatching.item.domain.order.dto.OrderGrantResponse;
import com.comatching.item.domain.order.dto.OrderRefundResponse;
import com.comatching.item.domain.order.enums.GrantStatus;
import com.comatching.item.domain.order.enums.RefundStatus;
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
	@Operation(summary = "미지급 주문의 수동 환불 시작", description = "지급 재시도를 차단하고 이체할 전액을 반환합니다.")
	@PostMapping("/requests/{orderId}/refund/start")
	public ResponseEntity<ApiResponse<OrderRefundResponse>> startRefund(@PathVariable Long orderId, @CurrentMember MemberInfo admin) {
		return ResponseEntity.ok(ApiResponse.ok(adminPaymentService.startRefund(orderId, admin.memberId())));
	}

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(summary = "수동 이체 후 환불 완료 확인", description = "완료 기록과 메일 이벤트를 함께 저장하고 중복 요청은 기존 결과를 반환합니다.")
	@PostMapping("/requests/{orderId}/refund/complete")
	public ResponseEntity<ApiResponse<OrderRefundResponse>> completeRefund(@PathVariable Long orderId, @CurrentMember MemberInfo admin) {
		return ResponseEntity.ok(ApiResponse.ok(adminPaymentService.completeRefund(orderId, admin.memberId())));
	}

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(summary = "수동 환불 목록", description = "기본값은 이체 확인 대기 중인 IN_PROGRESS입니다.")
	@GetMapping("/refunds")
	public ResponseEntity<ApiResponse<PagingResponse<OrderRefundResponse>>> getRefunds(
			@CurrentMember MemberInfo admin, @RequestParam(defaultValue = "IN_PROGRESS") RefundStatus status,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.ok(adminPaymentService.getRefunds(status, page, size)));
	}

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(summary = "승인 후 지급에 실패한 주문 목록", description = "주문 번호 내림차순, 페이지당 최대 100건입니다.")
	@GetMapping("/grants")
	public ResponseEntity<ApiResponse<PagingResponse<FailedGrantResponse>>> getFailedGrants(
			@CurrentMember MemberInfo admin,
			@RequestParam(defaultValue = "FAILED") GrantStatus grantStatus,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		if (grantStatus != GrantStatus.FAILED) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "지급 실패(FAILED) 주문만 조회할 수 있습니다.");
		}
		return ResponseEntity.ok(ApiResponse.ok(adminPaymentService.getFailedGrants(page, size)));
	}

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(summary = "주문 승인·지급 상태 조회")
	@GetMapping("/requests/{orderId}/grant")
	public ResponseEntity<ApiResponse<OrderGrantResponse>> getGrant(@PathVariable Long orderId, @CurrentMember MemberInfo admin) {
		return ResponseEntity.ok(ApiResponse.ok(adminPaymentService.getGrant(orderId)));
	}

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(summary = "실패한 주문의 묶음 지급 재예약")
	@PostMapping("/requests/{orderId}/grant/retry")
	public ResponseEntity<ApiResponse<Void>> retryGrant(@PathVariable Long orderId, @CurrentMember MemberInfo admin) {
		adminPaymentService.retryGrant(orderId);
		return ResponseEntity.accepted().body(ApiResponse.ok());
	}

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
	@Operation(summary = "구매 승인 및 지급 예약", description = "입금이 확인된 요청 건을 승인하고 아이템 지급을 예약합니다.")
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
