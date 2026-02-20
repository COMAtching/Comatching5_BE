package com.comatching.item.domain.product.service;

import java.util.List;

import com.comatching.item.domain.product.dto.ProductResponse;

public interface ShopService {

	List<ProductResponse> getActiveProducts();

	void requestPurchase(Long memberId, Long productId);
}
