package com.comatching.item.domain.product.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

	private final ProductRepository productRepository;

	@Override
	public ProductResponse createProduct(ProductCreateRequest request) {
		Map<ItemType, Integer> rewardQuantityByType = validateRewards(request.rewards());
		Product product = Product.builder()
			.name(request.name().trim())
			.description(request.description().trim())
			.price(request.price())
			.displayOrder(request.displayOrder())
			.isActive(request.isActive())
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
	public List<ProductResponse> getProducts() {
		List<Product> products = productRepository.findAllProductsWithRewards();
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
