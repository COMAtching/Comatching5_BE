package com.comatching.item.domain.product.service;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.comatching.common.annotation.DistributedLock;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.member.OrdererInfoDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.item.repository.ItemRepository;
import com.comatching.item.domain.order.entity.Order;
import com.comatching.item.domain.order.entity.OrderItem;
import com.comatching.item.domain.order.repository.OrderRepository;
import com.comatching.item.domain.order.service.OrderOutboxService;
import com.comatching.item.domain.product.dto.ProductResponse;
import com.comatching.item.domain.product.dto.PurchaseLimitResponse;
import com.comatching.item.domain.product.dto.PurchasePendingStatusResponse;
import com.comatching.item.domain.product.entity.Product;
import com.comatching.item.domain.product.entity.ProductReward;
import com.comatching.item.domain.product.enums.PurchaseBlockReason;
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
	private static final Map<ItemType, Integer> PURCHASE_LIMITS = Map.of(
		ItemType.MATCHING_TICKET, 30,
		ItemType.OPTION_TICKET, 90
	);
	private static final List<ItemType> LIMITED_ITEM_TYPES = List.of(
		ItemType.MATCHING_TICKET,
		ItemType.OPTION_TICKET
	);

	private final ProductRepository productRepository;
	private final OrderRepository orderRepository;
	private final ItemRepository itemRepository;
	private final UserOrderClient userOrderClient;
	private final OrderOutboxService orderOutboxService;

	@Override
	@Transactional(readOnly = true)
	public List<ProductResponse> getActiveProducts(Long memberId, Boolean isBundle) {
		List<Product> products = productRepository.findActiveProductsWithRewards(isBundle);
		fetchBonusRewards(products);
		LocalDateTime now = LocalDateTime.now();
		return products.stream()
			.map(product -> toMemberProductResponse(memberId, product, now))
			.filter(product -> Boolean.TRUE.equals(product.purchaseCountPurchasable()))
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

		validatePurchaseLimit(memberId, product, now);
		validatePurchaseCountLimit(memberId, product, now);

		OrdererInfoDto ordererInfo = userOrderClient.getOrdererInfo(memberId);
		String realName = normalizeRequiredText(ordererInfo.realName(), PaymentErrorCode.REAL_NAME_REQUIRED);
		String username = normalizeRequiredText(ordererInfo.nickname(), PaymentErrorCode.USERNAME_REQUIRED);

		Order order = Order.builder()
			.memberId(memberId)
			.productId(product.getId())
			.productCode(resolveProductCode(product))
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

	private ProductResponse toMemberProductResponse(Long memberId, Product product, LocalDateTime now) {
		String productCode = resolveProductCode(product);
		long usedPurchaseCount = orderRepository.countApprovedByMemberIdAndProductCode(memberId, productCode);
		long activePendingOrderCount = orderRepository.countActivePendingByMemberIdAndProductCode(memberId, productCode, now);

		Long remainingPurchaseCount = remainingCount(product.getPurchaseLimitPerMember(), usedPurchaseCount, activePendingOrderCount);
		PurchaseBlockReason blockReason = purchaseBlockReason(
			memberId,
			product,
			now,
			remainingPurchaseCount
		);

		return ProductResponse.from(
			product,
			usedPurchaseCount,
			remainingPurchaseCount,
			blockReason == PurchaseBlockReason.NONE,
			blockReason
		);
	}

	private void validatePurchaseCountLimit(Long memberId, Product product, LocalDateTime now) {
		if (product.isFirstPurchaseOnly() && orderRepository.existsApprovedOrActivePendingOrder(memberId, now)) {
			throw new BusinessException(PaymentErrorCode.FIRST_PURCHASE_ONLY);
		}

		String productCode = resolveProductCode(product);
		long usedPurchaseCount = orderRepository.countApprovedByMemberIdAndProductCode(memberId, productCode);
		long activePendingOrderCount = orderRepository.countActivePendingByMemberIdAndProductCode(memberId, productCode, now);
		Long remainingPurchaseCount = remainingCount(product.getPurchaseLimitPerMember(), usedPurchaseCount, activePendingOrderCount);
		if (remainingPurchaseCount != null && remainingPurchaseCount <= 0) {
			throw new BusinessException(PaymentErrorCode.PRODUCT_PURCHASE_LIMIT_EXCEEDED);
		}
	}

	private PurchaseBlockReason purchaseBlockReason(
		Long memberId,
		Product product,
		LocalDateTime now,
		Long remainingPurchaseCount
	) {
		if (product.isFirstPurchaseOnly() && orderRepository.existsApprovedOrActivePendingOrder(memberId, now)) {
			return PurchaseBlockReason.FIRST_PURCHASE_ONLY;
		}
		if (remainingPurchaseCount != null && remainingPurchaseCount <= 0) {
			return PurchaseBlockReason.PRODUCT_LIMIT_EXCEEDED;
		}
		return PurchaseBlockReason.NONE;
	}

	private Long remainingCount(Integer limit, long usedPurchaseCount, long activePendingOrderCount) {
		if (limit == null) {
			return null;
		}
		return Math.max(0, limit - usedPurchaseCount - activePendingOrderCount);
	}

	private String resolveProductCode(Product product) {
		if (StringUtils.hasText(product.getCode())) {
			return product.getCode().trim();
		}
		return "PRODUCT_ID_" + product.getId();
	}

	@Override
	@Transactional(readOnly = true)
	public PurchasePendingStatusResponse getMyPurchaseRequestStatus(Long memberId) {
		boolean hasPendingRequest = orderRepository.existsActivePendingOrder(memberId, LocalDateTime.now());
		return hasPendingRequest ? PurchasePendingStatusResponse.pending() : PurchasePendingStatusResponse.none();
	}

	@Override
	@Transactional(readOnly = true)
	public PurchaseLimitResponse getMyPurchaseLimits(Long memberId) {
		LocalDateTime now = LocalDateTime.now();
		return new PurchaseLimitResponse(
			LIMITED_ITEM_TYPES.stream()
				.map(itemType -> PurchaseLimitResponse.ItemPurchaseLimitResponse.of(
					itemType,
					itemRepository.sumUsableQuantityByMemberIdAndItemType(memberId, itemType),
					orderRepository.sumActivePendingQuantityByMemberIdAndItemType(memberId, itemType, now),
					PURCHASE_LIMITS.get(itemType)
				))
				.toList()
		);
	}

	private void validatePurchaseLimit(Long memberId, Product product, LocalDateTime now) {
		Map<ItemType, Integer> requestedQuantityByType = getRequestedQuantityByType(product);

		for (ItemType itemType : LIMITED_ITEM_TYPES) {
			int requestedQuantity = requestedQuantityByType.getOrDefault(itemType, 0);
			if (requestedQuantity == 0) {
				continue;
			}

			long ownedQuantity = itemRepository.sumUsableQuantityByMemberIdAndItemType(memberId, itemType);
			long pendingQuantity = orderRepository.sumActivePendingQuantityByMemberIdAndItemType(memberId, itemType, now);
			int maxQuantity = PURCHASE_LIMITS.get(itemType);
			if (ownedQuantity + pendingQuantity + requestedQuantity > maxQuantity) {
				throw new BusinessException(PaymentErrorCode.PURCHASE_LIMIT_EXCEEDED);
			}
		}
	}

	private Map<ItemType, Integer> getRequestedQuantityByType(Product product) {
		Map<ItemType, Integer> requestedQuantityByType = new EnumMap<>(ItemType.class);
		for (ProductReward reward : product.getRewards()) {
			requestedQuantityByType.merge(reward.getItemType(), reward.getQuantity(), Integer::sum);
		}
		return requestedQuantityByType;
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
