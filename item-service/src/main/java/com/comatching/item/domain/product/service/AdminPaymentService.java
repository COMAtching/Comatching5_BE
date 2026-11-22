package com.comatching.item.domain.product.service;

import org.springframework.data.domain.Pageable;

import com.comatching.common.dto.response.PagingResponse;
import com.comatching.item.domain.order.dto.FailedGrantResponse;
import com.comatching.item.domain.order.dto.OrderGrantResponse;
import com.comatching.item.domain.order.dto.OrderRefundResponse;
import com.comatching.item.domain.order.enums.RefundStatus;
import com.comatching.item.domain.product.dto.PurchaseRequestDto;

public interface AdminPaymentService {

	PagingResponse<PurchaseRequestDto> getPendingRequests(Pageable pageable);

	PagingResponse<FailedGrantResponse> getFailedGrants(int page, int size);

	OrderGrantResponse getGrant(Long orderId);

	void retryGrant(Long orderId);

	OrderRefundResponse startRefund(Long orderId, Long adminId);

	OrderRefundResponse completeRefund(Long orderId, Long adminId);

	PagingResponse<OrderRefundResponse> getRefunds(RefundStatus status, int page, int size);

	void approvePurchase(Long requestId, Long adminId);

	void rejectPurchase(Long requestId, Long adminId);
}
