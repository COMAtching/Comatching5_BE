package com.comatching.item.domain.product.dto;

import java.util.List;

import com.comatching.common.domain.enums.ItemType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "관리자 상품 등록 요청")
public record ProductCreateRequest(
		@Schema(description = "상품명", example = "매칭권 10개 (+옵션권 5개)", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "상품명은 필수입니다.")
		String name,

		@Schema(description = "상품 코드. 생략하면 서버가 자동 생성합니다. 구매 제한 판정에 사용하는 안정적인 식별자입니다.", example = "MATCHING_TICKET_10")
		@Pattern(regexp = "^\\s*$|^\\s*[A-Z0-9_-]+\\s*$", message = "상품 코드는 비워두거나 영문 대문자, 숫자, _, -만 사용할 수 있습니다.")
		String code,

		@Schema(description = "상품 설명. 50자 이하의 실제 설명 문구입니다.", example = "매칭권과 옵션권을 함께 충전해요.", maxLength = 50, requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "상품 설명은 필수입니다.")
		@Size(max = 50, message = "상품 설명은 50자 이하여야 합니다.")
		String description,

		@Schema(description = "상품 가격", example = "9000", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
		@Min(value = 1, message = "가격은 1원 이상이어야 합니다.")
		int price,

		@Schema(description = "상품 노출 순서. 낮을수록 먼저 노출됩니다.", example = "3", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
		@Min(value = 0, message = "상품 노출 순서는 0 이상이어야 합니다.")
		int displayOrder,

		@Schema(description = "판매 활성 여부. false이면 사용자 상품 목록과 구매 대상에서 제외됩니다.", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean isActive,

		@Schema(description = "번들 상품 여부. true이면 번들 상품 필터에 포함됩니다.", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean isBundle,

		@Schema(description = "계정당 이 상품 구매 가능 횟수. null이면 무제한입니다.", example = "1")
		@Min(value = 1, message = "계정당 상품 구매 제한은 1 이상이어야 합니다.")
		Integer purchaseLimitPerMember,

		@Schema(description = "진짜 첫 구매 전용 상품 여부. true이면 기존 승인/유효 대기 구매가 있는 사용자는 구매할 수 없습니다.", example = "false")
		boolean firstPurchaseOnly,

		@Schema(description = "실제 지급 구성품 목록. 구매 승인 시 이 수량이 그대로 지급됩니다.", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotEmpty(message = "구성품은 최소 1개 이상이어야 합니다.")
		List<@Valid ProductRewardCreateRequest> rewards,

		@Schema(description = "프론트 표시용 보너스 구성품 목록. 실제 지급은 rewards 기준이며, 보너스 수량은 동일 itemType의 실제 지급 수량 이하여야 합니다.")
		List<@Valid ProductRewardCreateRequest> bonusRewards
	) {
		@Schema(description = "상품 구성품 또는 보너스 구성품")
		public record ProductRewardCreateRequest(
			@Schema(description = "아이템 타입", example = "MATCHING_TICKET", requiredMode = Schema.RequiredMode.REQUIRED)
			@NotNull(message = "아이템 타입은 필수입니다.")
			ItemType itemType,

			@Schema(description = "수량", example = "10", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
			@Min(value = 1, message = "구성품 수량은 1 이상이어야 합니다.")
			int quantity
		) {
	}
}
