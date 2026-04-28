package com.comatching.item.infra.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.item.domain.product.dto.ProductCreateRequest;
import com.comatching.item.domain.product.dto.ProductResponse;
import com.comatching.item.domain.product.service.AdminProductService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Admin Product API", description = "관리자 전용 상품 관리")
@RestController
@RequestMapping("/api/v1/admin/shop")
@RequiredArgsConstructor
public class AdminProductController {

	private final AdminProductService adminProductService;

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(
		summary = "상품 등록",
		description = "상품명, 50자 이하 설명, 가격, 노출 순서, 활성 여부, 번들 여부, 실제 지급 구성품, 프론트 표시용 보너스 구성품을 입력받아 신규 상품을 등록합니다. 실제 지급은 rewards 기준이며 bonusRewards는 표시용입니다."
	)
	@PostMapping("/products")
	public ResponseEntity<ApiResponse<ProductResponse>> createProduct(
		@CurrentMember MemberInfo memberInfo,
		@RequestBody @Valid ProductCreateRequest request
	) {
		ProductResponse response = adminProductService.createProduct(request);
		return ResponseEntity.ok(ApiResponse.ok(response));
	}

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(
		summary = "관리자 상품 목록 조회",
		description = "활성/비활성 전체 상품 목록을 displayOrder 오름차순, id 오름차순으로 조회합니다. isBundle query parameter로 번들/비번들 상품을 필터링할 수 있습니다."
	)
	@GetMapping("/products")
	public ResponseEntity<ApiResponse<List<ProductResponse>>> getProducts(
		@CurrentMember MemberInfo memberInfo,
		@RequestParam(required = false) Boolean isBundle
	) {
		return ResponseEntity.ok(ApiResponse.ok(adminProductService.getProducts(isBundle)));
	}

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(summary = "상품 삭제", description = "상품을 실제 삭제하지 않고 isActive=false로 변경하여 판매 중지 처리합니다.")
	@DeleteMapping("/products/{productId}")
	public ResponseEntity<ApiResponse<Void>> deleteProduct(
		@CurrentMember MemberInfo memberInfo,
		@PathVariable Long productId
	) {
		adminProductService.deleteProduct(productId);
		return ResponseEntity.ok(ApiResponse.ok());
	}
}
