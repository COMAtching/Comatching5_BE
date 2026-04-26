package com.comatching.item.domain.product.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.comatching.common.annotation.DistributedLock;
import com.comatching.common.dto.member.OrdererInfoDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.order.entity.Order;
import com.comatching.item.domain.order.entity.OrderItem;
import com.comatching.item.domain.order.repository.OrderRepository;
import com.comatching.item.domain.order.service.OrderOutboxService;
import com.comatching.item.domain.product.dto.ProductResponse;
import com.comatching.item.domain.product.dto.PurchasePendingStatusResponse;
import com.comatching.item.domain.product.entity.Product;
import com.comatching.item.domain.product.repository.ProductRepository;
import com.comatching.item.global.exception.ItemErrorCode;
import com.comatching.item.global.exception.PaymentErrorCode;
import com.comatching.item.infra.client.UserOrderClient;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class ShopServiceImpl implements ShopService {

	private static final int ORDER_EXPIRE_MINUTES = 10;

	private final ProductRepository productRepository;
	private final OrderRepository orderRepository;
	private final UserOrderClient userOrderClient;
	private final OrderOutboxService orderOutboxService;

	@Override
	@Transactional(readOnly = true)
	public List<ProductResponse> getActiveProducts() {
		List<Product> products = productRepository.findActiveProductsWithRewards();
		fetchBonusRewards(products);
		return products.stream()
			.map(ProductResponse::from)
			.toList();
	}

	@Override
	@DistributedLock(key = "order:pending", identifier = "#memberId")
	public void requestPurchase(Long memberId, Long productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new BusinessException(ItemErrorCode.PRODUCT_NOT_FOUND));

		if (!product.isActive()) {
			throw new BusinessException(ItemErrorCode.PRODUCT_NOT_AVAILABLE);
		}

		LocalDateTime now = LocalDateTime.now();
		boolean hasPendingRequest = orderRepository.existsActivePendingOrder(memberId, now);
		if (hasPendingRequest) {
			throw new BusinessException(PaymentErrorCode.PENDING_REQUEST_ALREADY_EXISTS);
		}

		OrdererInfoDto ordererInfo = userOrderClient.getOrdererInfo(memberId);
		String realName = normalizeRequiredText(ordererInfo.realName(), PaymentErrorCode.REAL_NAME_REQUIRED);
		String username = normalizeRequiredText(ordererInfo.nickname(), PaymentErrorCode.USERNAME_REQUIRED);

		Order order = Order.builder()
			.memberId(memberId)
			.requestedItemName(product.getName())
			.requesterRealName(realName)
			.requesterUsername(username)
			.requestedPrice(product.getPrice())
			.expectedPrice(product.getPrice())
			.requestedAt(now)
			.expiresAt(now.plusMinutes(ORDER_EXPIRE_MINUTES))
			.build();

		product.getRewards().forEach(reward -> order.addOrderItem(
			OrderItem.builder()
				.itemType(reward.getItemType())
				.quantity(reward.getQuantity())
				.build()
		));

		orderRepository.save(order);
		orderOutboxService.enqueueOrderCreated(order);
	}

	@Override
	@Transactional(readOnly = true)
	public PurchasePendingStatusResponse getMyPurchaseRequestStatus(Long memberId) {
		boolean hasPendingRequest = orderRepository.existsActivePendingOrder(memberId, LocalDateTime.now());
		return hasPendingRequest ? PurchasePendingStatusResponse.pending() : PurchasePendingStatusResponse.none();
	}

	private String normalizeRequiredText(String value, PaymentErrorCode errorCode) {
		if (!StringUtils.hasText(value)) {
			throw new BusinessException(errorCode);
		}
		return value.trim();
	}

	private void fetchBonusRewards(List<Product> products) {
		if (products.isEmpty()) {
			return;
		}

		List<Long> productIds = products.stream()
			.map(Product::getId)
			.toList();
		productRepository.fetchBonusRewardsByProductIds(productIds);
	}
}
