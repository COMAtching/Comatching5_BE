package com.comatching.item.infra.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.item.domain.order.dto.MyPurchaseOrder;
import com.comatching.item.domain.order.repository.MyPurchaseOrderQuery;
import com.comatching.item.domain.order.repository.MyPurchaseOrderQuery.Scope;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class MyPurchaseOrderController {
	private final MyPurchaseOrderQuery orders;

	@Operation(summary = "내 충전 주문 내역 조회")
	@GetMapping("/api/v1/shop/purchase/orders")
	public ApiResponse<PagingResponse<MyPurchaseOrder>> orders(
		@CurrentMember MemberInfo member, @RequestParam(defaultValue = "ALL") Scope scope,
		@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size
	) {
		if (page < 0 || size < 1 || size > 100) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "페이지는 0 이상, 조회 건수는 1~100으로 지정해 주세요.");
		}
		return ApiResponse.ok(orders.find(member.memberId(), scope, PageRequest.of(page, size)));
	}
}
