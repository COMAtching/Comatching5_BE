package com.comatching.item.domain.product.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.comatching.common.domain.enums.ItemType;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

@DisplayName("ProductCreateRequest 검증 테스트")
class ProductCreateRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidRequests")
	@DisplayName("상품 생성 요청의 Bean Validation 제약을 검증한다")
	void shouldValidateProductCreateRequest(String name, ProductCreateRequest request, String expectedMessage) {
		// when
		Set<ConstraintViolation<ProductCreateRequest>> violations = validator.validate(request);

		// then
		assertThat(violations).extracting(ConstraintViolation::getMessage)
			.contains(expectedMessage);
	}

	@Test
	@DisplayName("보너스 구성품은 생략할 수 있다")
	void shouldAllowNullBonusRewards() {
		// given
		ProductCreateRequest request = new ProductCreateRequest(
			"신규 번들",
			"상품 설명",
			1000,
			1,
			true,
			List.of(reward(ItemType.MATCHING_TICKET, 1)),
			null
		);

		// when
		Set<ConstraintViolation<ProductCreateRequest>> violations = validator.validate(request);

		// then
		assertThat(violations).isEmpty();
	}

	private static Stream<Arguments> invalidRequests() {
		return Stream.of(
			Arguments.of(
				"상품명 공백",
				request(" ", "상품 설명", 1000, 1, List.of(reward(ItemType.MATCHING_TICKET, 1)), List.of()),
				"상품명은 필수입니다."
			),
			Arguments.of(
				"상품 설명 공백",
				request("신규 번들", " ", 1000, 1, List.of(reward(ItemType.MATCHING_TICKET, 1)), List.of()),
				"상품 설명은 필수입니다."
			),
			Arguments.of(
				"상품 설명 길이 초과",
				request(
					"신규 번들",
					"123456789012345678901234567890123456789012345678901",
					1000,
					1,
					List.of(reward(ItemType.MATCHING_TICKET, 1)),
					List.of()
				),
				"상품 설명은 50자 이하여야 합니다."
			),
			Arguments.of(
				"가격 최소값",
				request("신규 번들", "상품 설명", 0, 1, List.of(reward(ItemType.MATCHING_TICKET, 1)), List.of()),
				"가격은 1원 이상이어야 합니다."
			),
			Arguments.of(
				"노출 순서 최소값",
				request("신규 번들", "상품 설명", 1000, -1, List.of(reward(ItemType.MATCHING_TICKET, 1)), List.of()),
				"상품 노출 순서는 0 이상이어야 합니다."
			),
			Arguments.of(
				"구성품 필수",
				request("신규 번들", "상품 설명", 1000, 1, List.of(), List.of()),
				"구성품은 최소 1개 이상이어야 합니다."
			),
			Arguments.of(
				"구성품 아이템 타입 필수",
				request("신규 번들", "상품 설명", 1000, 1, List.of(reward(null, 1)), List.of()),
				"아이템 타입은 필수입니다."
			),
			Arguments.of(
				"구성품 수량 최소값",
				request("신규 번들", "상품 설명", 1000, 1, List.of(reward(ItemType.MATCHING_TICKET, 0)), List.of()),
				"구성품 수량은 1 이상이어야 합니다."
			),
			Arguments.of(
				"보너스 아이템 타입 필수",
				request(
					"신규 번들",
					"상품 설명",
					1000,
					1,
					List.of(reward(ItemType.MATCHING_TICKET, 1)),
					List.of(reward(null, 1))
				),
				"아이템 타입은 필수입니다."
			),
			Arguments.of(
				"보너스 수량 최소값",
				request(
					"신규 번들",
					"상품 설명",
					1000,
					1,
					List.of(reward(ItemType.MATCHING_TICKET, 1)),
					List.of(reward(ItemType.MATCHING_TICKET, 0))
				),
				"구성품 수량은 1 이상이어야 합니다."
			)
		);
	}

	private static ProductCreateRequest request(
		String name,
		String description,
		int price,
		int displayOrder,
		List<ProductCreateRequest.ProductRewardCreateRequest> rewards,
		List<ProductCreateRequest.ProductRewardCreateRequest> bonusRewards
	) {
		return new ProductCreateRequest(name, description, price, displayOrder, true, rewards, bonusRewards);
	}

	private static ProductCreateRequest.ProductRewardCreateRequest reward(ItemType itemType, int quantity) {
		return new ProductCreateRequest.ProductRewardCreateRequest(itemType, quantity);
	}
}
