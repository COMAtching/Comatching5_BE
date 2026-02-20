package com.comatching.item.infra.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.item.domain.product.dto.ProductResponse;
import com.comatching.item.domain.product.service.ShopService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Shop API", description = "아이템 상점 및 구매 요청")
@RestController
@RequestMapping("/api/v1/shop")
@RequiredArgsConstructor
public class ShopController {

	private final ShopService shopService;

	@Operation(summary = "상품 목록 조회", description = "현재 판매 중인 모든 아이템 패키지 목록을 조회합니다.")
	@GetMapping("/products")
	public ResponseEntity<ApiResponse<List<ProductResponse>>> getActiveProducts() {
		return ResponseEntity.ok(ApiResponse.ok(shopService.getActiveProducts()));
	}

	@Operation(summary = "아이템 구매 요청", description = "특정 상품에 대한 구매 요청(입금 대기)을 생성합니다.")
	@PostMapping("/purchase/{productId}")
	public ResponseEntity<ApiResponse<Void>> requestPurchase(
		@CurrentMember MemberInfo memberInfo,
		@PathVariable Long productId
	) {
		shopService.requestPurchase(memberInfo.memberId(), productId);
		return ResponseEntity.ok(ApiResponse.ok());
	}
}