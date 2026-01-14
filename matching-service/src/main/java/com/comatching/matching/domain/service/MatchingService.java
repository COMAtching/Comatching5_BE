package com.comatching.matching.domain.service;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.Hobby;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.dto.MatchingResponse;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.dto.MatchingHistoryResponse;

public interface MatchingService {

	MatchingResponse match(Long memberId, MatchingRequest request);

	PagingResponse<MatchingHistoryResponse> getMyMatchingHistory(
		Long memberId,
		LocalDateTime startDate,
		LocalDateTime endDate,
		Pageable pageable);
}
