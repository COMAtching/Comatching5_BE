package com.comatching.matching.domain.dto;

public record FavoriteHistoryRequest(
	Long historyId,
	boolean favorite
) {
}
