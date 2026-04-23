package com.comatching.item.infra.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

@Tag(name = "Admin Product API", description = "관리자 전용 상품 등록")
@RestController
@RequestMapping("/api/v1/admin/shop")
@RequiredArgsConstructor
public class AdminProductController {

	private final AdminProductService adminProductService;

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(summary = "상품 등록", description = "상품명/가격/활성여부/구성품을 입력받아 신규 상품을 등록합니다.")
	@PostMapping("/products")
	public ResponseEntity<ApiResponse<ProductResponse>> createProduct(
		@CurrentMember MemberInfo memberInfo,
		@RequestBody @Valid ProductCreateRequest request
	) {
		ProductResponse response = adminProductService.createProduct(request);
		return ResponseEntity.ok(ApiResponse.ok(response));
	}
}
