package com.comatching.common.dto.event.matching;

import lombok.Builder;

@Builder
public record MatchingSuccessEvent(
	Long matchingId,
	Long initiatorUserId,
	Long targetUserId
) {
}
