package com.comatching.item.domain.product.service;

import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.item.domain.product.dto.ProductCreateRequest;
import com.comatching.item.domain.product.dto.ProductResponse;
import com.comatching.item.domain.product.entity.Product;
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
		if (!StringUtils.hasText(request.name())) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "상품명은 공백일 수 없습니다.");
		}
		if (request.price() <= 0) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "상품 가격은 1원 이상이어야 합니다.");
		}
		if (request.rewards() == null || request.rewards().isEmpty()) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "상품 구성품은 최소 1개 이상이어야 합니다.");
		}

		Set<ItemType> duplicateCheck = new HashSet<>();
		Product product = Product.builder()
			.name(request.name().trim())
			.price(request.price())
			.isActive(request.isActive())
			.build();

		request.rewards().forEach(rewardRequest -> {
			if (rewardRequest.quantity() <= 0) {
				throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "구성품 수량은 1 이상이어야 합니다.");
			}
			if (!duplicateCheck.add(rewardRequest.itemType())) {
				throw new BusinessException(
					GeneralErrorCode.INVALID_INPUT_VALUE,
					"동일 아이템 타입은 한 번만 등록할 수 있습니다: " + rewardRequest.itemType().name()
				);
			}

			ProductReward reward = ProductReward.builder()
				.itemType(rewardRequest.itemType())
				.quantity(rewardRequest.quantity())
				.build();
			product.addReward(reward);
		});

		Product saved = productRepository.save(product);
		return ProductResponse.from(saved);
	}
}
