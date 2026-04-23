package com.comatching.item.domain.product.dto;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.order.entity.Order;
import com.comatching.item.domain.order.enums.OrderStatus;

import java.time.LocalDateTime;

public record PurchaseRequestDto(
	Long requestId,
	Long memberId,
	String requestedItemName,
	String requesterRealName,
	String requesterUsername,
	int optionTicketQty,
	int matchingTicketQty,
	int requestedPrice,
	int expectedPrice,
	OrderStatus status,
	LocalDateTime requestedAt,
	LocalDateTime expiresAt
) {
	public static PurchaseRequestDto from(Order request) {
		return new PurchaseRequestDto(
			request.getId(),
			request.getMemberId(),
			request.getRequestedItemName(),
			request.getRequesterRealName(),
			request.getRequesterUsername(),
			getQuantity(request, ItemType.OPTION_TICKET),
			getQuantity(request, ItemType.MATCHING_TICKET),
			request.getRequestedPrice(),
			request.getExpectedPrice(),
			request.getStatus(),
			request.getRequestedAt(),
			request.getExpiresAt()
		);
	}

	private static int getQuantity(Order request, ItemType itemType) {
		return request.getOrderItems().stream()
			.filter(orderItem -> orderItem.getItemType() == itemType)
			.mapToInt(orderItem -> orderItem.getQuantity())
			.sum();
	}
}
