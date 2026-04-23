package com.comatching.item.domain.product.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.domain.enums.ItemRoute;
import com.comatching.common.dto.item.AddItemRequest;
import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.item.service.ItemService;
import com.comatching.item.domain.product.dto.PurchaseRequestDto;
import com.comatching.item.domain.product.entity.Product;
import com.comatching.item.domain.product.entity.ProductReward;
import com.comatching.item.domain.product.entity.PurchaseRequest;
import com.comatching.item.domain.product.enums.PurchaseStatus;
import com.comatching.item.domain.product.repository.ProductRepository;
import com.comatching.item.domain.product.repository.PurchaseRequestRepository;
import com.comatching.item.global.exception.ItemErrorCode;
import com.comatching.item.global.exception.PaymentErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminPaymentServiceImpl implements AdminPaymentService {

	private final PurchaseRequestRepository purchaseRequestRepository;
	private final ProductRepository productRepository;
	private final ItemService itemService;

	// 상수: 구매 아이템의 유효기간 (약 100년)
	private static final int PURCHASED_ITEM_EXPIRE_DAYS = 36500;

	@Override
	@Transactional(readOnly = true)
	public List<PurchaseRequestDto> getPendingRequests() {
		// PENDING 상태인 요청을 최신순으로 조회하여 DTO로 변환
		return purchaseRequestRepository.findAllByStatusOrderByRequestedAtDesc(PurchaseStatus.PENDING)
			.stream()
			.map(PurchaseRequestDto::from)
			.toList();
	}

	@Override
	public void approvePurchase(Long requestId) {
		// 1. 요청 조회
		PurchaseRequest request = purchaseRequestRepository.findById(requestId)
			.orElseThrow(() -> new BusinessException(PaymentErrorCode.REQUEST_NOT_FOUND));

		// 2. 상태 검증 (이미 처리된 건인지)
		if (request.getStatus() != PurchaseStatus.PENDING) {
			throw new BusinessException(PaymentErrorCode.ALREADY_PROCESSED);
		}

		// 3. 요청 상태 변경 (승인)
		request.approve();

		// 4. 상품 정보 조회 (구성품 확인용)
		Product product = productRepository.findById(request.getProductId())
			.orElseThrow(() -> new BusinessException(ItemErrorCode.PRODUCT_NOT_FOUND));

		// 5. 구성품 지급 (루프)
		for (ProductReward reward : product.getRewards()) {
			AddItemRequest addItemRequest = new AddItemRequest(
				reward.getItemType(),
				reward.getQuantity(),
				ItemRoute.CHARGE, // 경로는 '충전'
				PURCHASED_ITEM_EXPIRE_DAYS // 유효기간 설정
			);

			// 기존 ItemService의 addItem 재사용 (히스토리 저장 등 로직 포함됨)
			itemService.addItem(request.getMemberId(), addItemRequest);
		}
	}
}