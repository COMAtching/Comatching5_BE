package com.comatching.item.domain.product.service;

import com.comatching.common.operations.Feature;
import com.comatching.common.operations.FeatureGate;

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
import com.comatching.item.domain.order.config.PaymentOrderProperties;
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

import com.comatching.item.infra.bankdeposit.BankApprovalGate;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class ShopServiceImpl implements ShopService {
	@Autowired(required = false)
	private BankApprovalGate depositGate;

	private final FeatureGate features;

	private static final String DISCOUNT_MATCHING_TICKET_CODE = "DISCOUNT_MATCHING_TICKET_1";

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
	private final PaymentOrderProperties paymentOrderProperties;

	@Override
	@Transactional(readOnly = true)
	public List<ProductResponse> getActiveProducts(Long memberId, Boolean isBundle) {
		features.requireOpen(Feature.SHOP_PAYMENT);
		List<Product> products = productRepository.findActiveProductsWithRewards(isBundle);
		fetchBonusRewards(products);
		LocalDateTime now = LocalDateTime.now();
		Map<ItemType, Integer> capacity = inventoryCapacity(memberId, now);
		boolean pendingOrder = orderRepository.existsActivePendingOrder(memberId, now);
		return products.stream()
			.map(product -> {
				PurchaseAllowance limits = allowance(memberId, product, now, capacity);
				return ProductResponse.from(product, limits.used(), limits.remaining(), pendingOrder ? 0 : limits.maxQuantity(),
					limits.reason() == PurchaseBlockReason.NONE, limits.reason());
			})
			.filter(product -> Boolean.TRUE.equals(product.purchaseCountPurchasable())
				|| DISCOUNT_MATCHING_TICKET_CODE.equals(product.code()))
			.toList();
	}

	@Override
	@DistributedLock(key = "order:pending", identifier = "#memberId")
	public void requestPurchase(Long memberId, Long productId, int quantity) {
		features.requireOpen(Feature.SHOP_PAYMENT);
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

		if (quantity < 1) throw new BusinessException(PaymentErrorCode.INVALID_ORDER_QUANTITY);
		PurchaseAllowance limits = allowance(memberId, product, now, inventoryCapacity(memberId, now));
		if (limits.reason() == PurchaseBlockReason.FIRST_PURCHASE_ONLY) {
			throw new BusinessException(PaymentErrorCode.FIRST_PURCHASE_ONLY);
		}
		if (limits.remaining() != null && quantity > limits.remaining()) {
			throw new BusinessException(PaymentErrorCode.PRODUCT_PURCHASE_LIMIT_EXCEEDED);
		}
		if (quantity > limits.maxQuantity()) throw new BusinessException(PaymentErrorCode.PURCHASE_LIMIT_EXCEEDED);
		int totalPrice = Math.multiplyExact(product.getPrice(), quantity);

		OrdererInfoDto ordererInfo = userOrderClient.getOrdererInfo(memberId);
		String realName = normalizeRequiredText(ordererInfo.realName(), PaymentErrorCode.REAL_NAME_REQUIRED);
		String username = normalizeRequiredText(ordererInfo.nickname(), PaymentErrorCode.USERNAME_REQUIRED);

		// The bank matcher must see this order or a later creation timestamp.
		// Take the lock after the user-service call so unrelated buyers can fetch their details concurrently.
		if (depositGate != null) {
			depositGate.lock();
			now = LocalDateTime.now();
		}
		Order order = Order.builder()
			.memberId(memberId)
			.productId(product.getId())
			.productCode(resolveProductCode(product))
			.requestedItemName(product.getName())
			.requesterRealName(realName)
			.requesterUsername(username)
			.productQuantity(quantity)
			.requestedPrice(totalPrice)
			.expectedPrice(totalPrice)
			.requestedAt(now)
			.expiresAt(now.plusMinutes(paymentOrderProperties.expireMinutes()))
			.build();

		product.getRewards().forEach(reward -> order.addOrderItem(
			OrderItem.builder()
				.itemType(reward.getItemType())
				.quantity(Math.multiplyExact(reward.getQuantity(), quantity))
				.build()
		));

		orderRepository.save(order);
		orderOutboxService.enqueueOrderCreated(order);
	}

	private record PurchaseAllowance(long used, Long remaining, int maxQuantity, PurchaseBlockReason reason) {}

	private PurchaseAllowance allowance(Long memberId, Product product, LocalDateTime now, Map<ItemType, Integer> capacity) {
		String code = resolveProductCode(product);
		long used = usedPurchaseCount(memberId, code);
		Long remaining = remainingCount(product.getPurchaseLimitPerMember(), used, activePendingPurchaseCount(memberId, code, now));
		PurchaseBlockReason reason = purchaseBlockReason(memberId, product, now, remaining);
		int maxQuantity = maximumOrderQuantity(product, capacity);
		if (remaining != null) maxQuantity = (int)Math.min(maxQuantity, remaining);
		if (reason != PurchaseBlockReason.NONE) maxQuantity = 0;
		return new PurchaseAllowance(used, remaining, maxQuantity, reason);
	}

	private static int maximumOrderQuantity(Product product, Map<ItemType, Integer> capacity) {
		int max = product.getPrice() > 0 ? Integer.MAX_VALUE / product.getPrice() : Integer.MAX_VALUE;
		Map<ItemType, Long> unitsPerProduct = new EnumMap<>(ItemType.class);
		for (ProductReward reward : product.getRewards()) {
			if (reward.getQuantity() <= 0) return 0;
			unitsPerProduct.merge(reward.getItemType(), (long)reward.getQuantity(), Long::sum);
		}
		for (var units : unitsPerProduct.entrySet()) {
			long available = capacity.getOrDefault(units.getKey(), Integer.MAX_VALUE);
			max = (int)Math.min(max, available / units.getValue());
		}
		return product.getPrice() >= 0 ? max : 0;
	}

	private Map<ItemType, Integer> inventoryCapacity(Long memberId, LocalDateTime now) {
		var capacity = new EnumMap<ItemType, Integer>(ItemType.class);
		for (ItemType type : LIMITED_ITEM_TYPES) {
			long limit = PURCHASE_LIMITS.get(type);
			long owned = itemRepository.sumUsableQuantityByMemberIdAndItemType(memberId, type);
			long pending = orderRepository.sumActivePendingQuantityByMemberIdAndItemType(memberId, type, now);
			long remaining = limit - Math.min(limit, Math.max(0, owned)) - Math.min(limit, Math.max(0, pending));
			capacity.put(type, (int)Math.max(0, remaining));
		}
		return capacity;
	}

	private long usedPurchaseCount(Long memberId, String productCode) {
		if (DISCOUNT_MATCHING_TICKET_CODE.equals(productCode)) {
			return orderRepository.sumApprovedDiscountUnits(
				memberId,
				productCode,
				ItemType.MATCHING_TICKET
			);
		}
		return orderRepository.sumApprovedProductUnits(memberId, productCode);
	}

	private long activePendingPurchaseCount(Long memberId, String productCode, LocalDateTime now) {
		if (DISCOUNT_MATCHING_TICKET_CODE.equals(productCode)) {
			return orderRepository.sumPendingDiscountUnits(
				memberId,
				productCode,
				ItemType.MATCHING_TICKET,
				now
			);
		}
		return orderRepository.sumPendingProductUnits(memberId, productCode, now);
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
