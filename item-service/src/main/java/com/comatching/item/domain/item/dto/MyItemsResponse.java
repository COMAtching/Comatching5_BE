package com.comatching.item.domain.item.dto;

import com.comatching.common.dto.response.PagingResponse;

public record MyItemsResponse(
	PagingResponse<ItemResponse> items,
	long matchingTicketCount,
	long optionTicketCount
) {
}
