package com.comatching.item.domain.product.dto;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.product.entity.Product;
import com.comatching.item.domain.product.entity.ProductBonusReward;
import com.comatching.item.domain.product.entity.ProductReward;
import com.comatching.item.domain.product.enums.PurchaseBlockReason;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "상품 응답")
public record ProductResponse(
	@Schema(description = "상품 ID", example = "3")
	Long id,

	@Schema(description = "상품명", example = "매칭권 10개 (+옵션권 5개)")
	String name,

	@Schema(description = "상품 코드", example = "MATCHING_TICKET_10")
	String code,

	@Schema(description = "상품 설명. 50자 이하입니다.", example = "매칭권과 옵션권을 함께 충전해요.")
	String description,

	@Schema(description = "상품 가격", example = "9000")
	int price,

	@Schema(description = "상품 노출 순서. 낮을수록 먼저 노출됩니다.", example = "3")
	int displayOrder,

	@Schema(description = "판매 활성 여부. 사용자 상품 목록은 true 상품만 반환합니다.", example = "true")
	boolean isActive,

	@Schema(description = "번들 상품 여부. isBundle 필터 조회에 사용됩니다.", example = "true")
	boolean isBundle,

	@Schema(description = "계정당 이 상품 구매 가능 횟수. null이면 무제한입니다.")
	Integer purchaseLimitPerMember,

	@Schema(description = "진짜 첫 구매 전용 상품 여부")
	boolean firstPurchaseOnly,

	@Schema(description = "현재 사용자 기준 승인 구매 횟수")
	Long usedPurchaseCount,

	@Schema(description = "현재 사용자 기준 상품별 남은 구매 횟수. 무제한이면 null입니다.")
	Long remainingPurchaseCount,

	@Schema(description = "현재 사용자 기준 구매 횟수 정책상 구매 가능 여부")
	Boolean purchaseCountPurchasable,

	@Schema(description = "구매 횟수 정책상 차단 사유")
	PurchaseBlockReason purchaseBlockReason,

	@Schema(description = "실제 지급 구성품 목록. 구매 승인 시 이 수량이 지급됩니다.")
	List<ProductRewardDto> rewards,

	@Schema(description = "프론트 표시용 보너스 구성품 목록. 실제 지급량은 rewards에 포함되어 있습니다.")
	List<ProductRewardDto> bonusRewards
) {
	public static ProductResponse from(Product product) {
		return new ProductResponse(
			product.getId(),
			product.getName(),
			product.getCode(),
			product.getDescription(),
			product.getPrice(),
			product.getDisplayOrder(),
			product.isActive(),
			product.isBundle(),
			product.getPurchaseLimitPerMember(),
			product.isFirstPurchaseOnly(),
			null,
			null,
			null,
			null,
			product.getRewards().stream()
				.map(ProductRewardDto::from)
				.toList(),
			product.getBonusRewards().stream()
				.map(ProductRewardDto::from)
				.toList()
		);
	}

	public static ProductResponse from(
		Product product,
		long usedPurchaseCount,
		Long remainingPurchaseCount,
		boolean purchaseCountPurchasable,
		PurchaseBlockReason purchaseBlockReason
	) {
		return new ProductResponse(
			product.getId(),
			product.getName(),
			product.getCode(),
			product.getDescription(),
			product.getPrice(),
			product.getDisplayOrder(),
			product.isActive(),
			product.isBundle(),
			product.getPurchaseLimitPerMember(),
			product.isFirstPurchaseOnly(),
			usedPurchaseCount,
			remainingPurchaseCount,
			purchaseCountPurchasable,
			purchaseBlockReason,
			product.getRewards().stream()
				.map(ProductRewardDto::from)
				.toList(),
			product.getBonusRewards().stream()
				.map(ProductRewardDto::from)
				.toList()
		);
	}

	@Schema(description = "상품 구성품 응답")
	public record ProductRewardDto(
		@Schema(description = "아이템 타입", example = "OPTION_TICKET")
		ItemType itemType,

		@Schema(description = "아이템 표시명", example = "옵션권")
		String itemName,

		@Schema(description = "수량", example = "5")
		int quantity
	) {
		public static ProductRewardDto from(ProductReward reward) {
			return new ProductRewardDto(
				reward.getItemType(),
				reward.getItemType().getName(),
				reward.getQuantity()
			);
		}

		public static ProductRewardDto from(ProductBonusReward reward) {
			return new ProductRewardDto(
				reward.getItemType(),
				reward.getItemType().getName(),
				reward.getQuantity()
			);
		}
	}
}
