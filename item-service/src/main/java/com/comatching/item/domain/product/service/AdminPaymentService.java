package com.comatching.item.domain.product.service;

import java.util.List;

import com.comatching.item.domain.product.dto.PurchaseRequestDto;

public interface AdminPaymentService {

	List<PurchaseRequestDto> getPendingRequests();

	void approvePurchase(Long requestId);
}
