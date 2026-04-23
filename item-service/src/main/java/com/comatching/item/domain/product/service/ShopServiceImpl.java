package com.comatching.item.domain.product.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.product.dto.ProductResponse;
import com.comatching.item.domain.product.entity.Product;
import com.comatching.item.domain.product.entity.PurchaseRequest;
import com.comatching.item.domain.product.repository.ProductRepository;
import com.comatching.item.domain.product.repository.PurchaseRequestRepository;
import com.comatching.item.global.exception.ItemErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class ShopServiceImpl implements ShopService {

	private final ProductRepository productRepository;
	private final PurchaseRequestRepository purchaseRequestRepository;

	@Override
	@Transactional(readOnly = true)
	public List<ProductResponse> getActiveProducts() {
		return productRepository.findByIsActiveTrue().stream()
			.map(ProductResponse::from)
			.toList();
	}

	@Override
	public void requestPurchase(Long memberId, Long productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new BusinessException(ItemErrorCode.PRODUCT_NOT_FOUND));

		if (!product.isActive()) {
			throw new BusinessException(ItemErrorCode.PRODUCT_NOT_AVAILABLE);
		}

		PurchaseRequest request = PurchaseRequest.builder()
			.memberId(memberId)
			.productId(product.getId())
			.productName(product.getName())
			.paymentPrice(product.getPrice()) // 구매 시점 가격 고정
			.build();

		purchaseRequestRepository.save(request);
	}
}