package com.comatching.item.domain.product.service;

import org.springframework.data.domain.Pageable;

import com.comatching.common.dto.response.PagingResponse;
import com.comatching.item.domain.product.dto.PurchaseRequestDto;

public interface AdminPaymentService {

	PagingResponse<PurchaseRequestDto> getPendingRequests(Pageable pageable);

	void approvePurchase(Long requestId, Long adminId);

	void rejectPurchase(Long requestId, Long adminId);
}
