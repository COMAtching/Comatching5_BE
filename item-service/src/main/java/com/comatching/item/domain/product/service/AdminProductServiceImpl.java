package com.comatching.item.domain.product.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.item.domain.product.dto.ProductCreateRequest;
import com.comatching.item.domain.product.dto.ProductResponse;
import com.comatching.item.domain.product.entity.Product;
import com.comatching.item.domain.product.entity.ProductBonusReward;
import com.comatching.item.domain.product.entity.ProductReward;
import com.comatching.item.domain.product.repository.ProductRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminProductServiceImpl implements AdminProductService {

	private static final String AUTO_PRODUCT_CODE_PREFIX = "PRODUCT_";
	private static final int AUTO_PRODUCT_CODE_MAX_ATTEMPTS = 5;

	private final ProductRepository productRepository;

	@Override
	public ProductResponse createProduct(ProductCreateRequest request) {
		Map<ItemType, Integer> rewardQuantityByType = validateRewards(request.rewards());
		String code = resolveProductCode(request.code());
		validateFirstPurchasePolicy(request);

		Product product = Product.builder()
			.name(request.name().trim())
			.code(code)
			.description(request.description().trim())
			.price(request.price())
			.displayOrder(request.displayOrder())
			.isActive(request.isActive())
			.isBundle(request.isBundle())
			.purchaseLimitPerMember(request.purchaseLimitPerMember())
			.firstPurchaseOnly(request.firstPurchaseOnly())
			.build();

		request.rewards().forEach(rewardRequest -> {
			ProductReward reward = ProductReward.builder()
				.itemType(rewardRequest.itemType())
				.quantity(rewardRequest.quantity())
				.build();
			product.addReward(reward);
		});

		validateBonusRewards(request.bonusRewards(), rewardQuantityByType);
		if (request.bonusRewards() != null) {
			request.bonusRewards().forEach(bonusRewardRequest -> {
				ProductBonusReward bonusReward = ProductBonusReward.builder()
					.itemType(bonusRewardRequest.itemType())
					.quantity(bonusRewardRequest.quantity())
					.build();
				product.addBonusReward(bonusReward);
			});
		}

		Product saved = productRepository.save(product);
		return ProductResponse.from(saved);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ProductResponse> getProducts(Boolean isBundle) {
		List<Product> products = productRepository.findAllProductsWithRewards(isBundle);
		fetchBonusRewards(products);
		return products.stream()
			.map(ProductResponse::from)
			.toList();
	}

	@Override
	public void deleteProduct(Long productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new BusinessException(GeneralErrorCode.NOT_FOUND, "상품을 찾을 수 없습니다."));

		product.deactivate();
	}

	private Map<ItemType, Integer> validateRewards(List<ProductCreateRequest.ProductRewardCreateRequest> rewards) {
		Set<ItemType> duplicateCheck = new HashSet<>();
		for (ProductCreateRequest.ProductRewardCreateRequest rewardRequest : rewards) {
			if (!duplicateCheck.add(rewardRequest.itemType())) {
				throw new BusinessException(
					GeneralErrorCode.INVALID_INPUT_VALUE,
					"동일 아이템 타입은 한 번만 등록할 수 있습니다: " + rewardRequest.itemType().name()
				);
			}
		}

		return rewards.stream()
			.collect(Collectors.toMap(
				ProductCreateRequest.ProductRewardCreateRequest::itemType,
				ProductCreateRequest.ProductRewardCreateRequest::quantity
			));
	}

	private void validateDuplicateCode(String code) {
		if (productRepository.existsByCode(code)) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "이미 사용 중인 상품 코드입니다: " + code);
		}
	}

	private String resolveProductCode(String requestCode) {
		if (StringUtils.hasText(requestCode)) {
			String code = requestCode.trim();
			validateDuplicateCode(code);
			return code;
		}
		return generateUniqueProductCode();
	}

	private String generateUniqueProductCode() {
		for (int attempt = 0; attempt < AUTO_PRODUCT_CODE_MAX_ATTEMPTS; attempt++) {
			String code = AUTO_PRODUCT_CODE_PREFIX + UUID.randomUUID()
				.toString()
				.replace("-", "")
				.toUpperCase(Locale.ROOT);
			if (!productRepository.existsByCode(code)) {
				return code;
			}
		}
		throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "상품 코드를 자동 생성하지 못했습니다. 다시 시도해주세요.");
	}

	private void validateFirstPurchasePolicy(ProductCreateRequest request) {
		if (!request.firstPurchaseOnly()) {
			return;
		}
		if (!Integer.valueOf(1).equals(request.purchaseLimitPerMember())) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "첫 구매 전용 상품의 계정당 구매 제한은 1이어야 합니다.");
		}
		if (request.isActive() && productRepository.existsByFirstPurchaseOnlyTrueAndIsActiveTrue()) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "활성 첫 구매 전용 상품은 하나만 등록할 수 있습니다.");
		}
	}

	private void validateBonusRewards(
		List<ProductCreateRequest.ProductRewardCreateRequest> bonusRewards,
		Map<ItemType, Integer> rewardQuantityByType
	) {
		if (bonusRewards == null || bonusRewards.isEmpty()) {
			return;
		}

		Set<ItemType> duplicateCheck = new HashSet<>();
		for (ProductCreateRequest.ProductRewardCreateRequest bonusReward : bonusRewards) {
			if (!duplicateCheck.add(bonusReward.itemType())) {
				throw new BusinessException(
					GeneralErrorCode.INVALID_INPUT_VALUE,
					"동일 보너스 아이템 타입은 한 번만 등록할 수 있습니다: " + bonusReward.itemType().name()
				);
			}

			Integer rewardQuantity = rewardQuantityByType.get(bonusReward.itemType());
			if (rewardQuantity == null) {
				throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "보너스는 실제 구성품에 포함된 아이템만 등록할 수 있습니다.");
			}
			if (bonusReward.quantity() > rewardQuantity) {
				throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "보너스 수량은 실제 지급 수량보다 클 수 없습니다.");
			}
		}
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
