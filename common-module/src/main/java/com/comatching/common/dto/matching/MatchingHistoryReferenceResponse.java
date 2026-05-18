package com.comatching.common.dto.matching;

public record MatchingHistoryReferenceResponse(
	Long historyId,
	boolean favorite
) {
	public static MatchingHistoryReferenceResponse empty() {
		return new MatchingHistoryReferenceResponse(null, false);
	}
}
