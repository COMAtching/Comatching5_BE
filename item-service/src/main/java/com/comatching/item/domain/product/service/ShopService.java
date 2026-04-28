package com.comatching.item.domain.product.service;

import java.util.List;

import com.comatching.item.domain.product.dto.ProductResponse;
import com.comatching.item.domain.product.dto.PurchasePendingStatusResponse;

public interface ShopService {

	List<ProductResponse> getActiveProducts(Boolean isBundle);

	void requestPurchase(Long memberId, Long productId);

	PurchasePendingStatusResponse getMyPurchaseRequestStatus(Long memberId);
}
