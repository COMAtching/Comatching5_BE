package com.comatching.matching.domain.service;

import java.time.LocalDateTime;

import org.springframework.data.domain.Pageable;

import com.comatching.common.dto.response.PagingResponse;
import com.comatching.matching.domain.dto.MatchingHistoryResponse;

public interface MatchingHistoryService {

	PagingResponse<MatchingHistoryResponse> getMyMatchingHistory(
		Long memberId,
		LocalDateTime startDate,
		LocalDateTime endDate,
		Pageable pageable,
		boolean favoriteOnly
	);

	void changeFavorite(Long memberId, Long historyId, boolean favorite);

}
