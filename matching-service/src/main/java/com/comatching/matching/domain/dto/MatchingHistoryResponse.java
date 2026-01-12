package com.comatching.matching.domain.dto;

import java.time.LocalDateTime;

import com.comatching.matching.domain.entity.MatchingHistory;

import lombok.Builder;

@Builder
public record MatchingHistoryResponse(
	Long historyId,
	Long partnerId,
	LocalDateTime matchedAt
) {
	public static MatchingHistoryResponse from(MatchingHistory history) {
		return MatchingHistoryResponse.builder()
			.historyId(history.getId())
			.partnerId(history.getPartnerId())
			.matchedAt(history.getMatchedAt())
			.build();
	}
}
