package com.comatching.matching.domain.dto;

import java.time.LocalDateTime;
import com.comatching.common.dto.member.ProfileResponse; // 공통 모듈의 프로필 DTO
import com.comatching.matching.domain.entity.MatchingHistory;

import lombok.Builder;

@Builder
public record MatchingHistoryResponse(
	Long historyId,
	ProfileResponse partner,
	boolean favorite,
	LocalDateTime matchedAt
) {
	public static MatchingHistoryResponse of(MatchingHistory history, ProfileResponse partnerProfile) {
		return MatchingHistoryResponse.builder()
			.historyId(history.getId())
			.partner(partnerProfile)
			.favorite(history.isFavorite())
			.matchedAt(history.getMatchedAt())
			.build();
	}
}