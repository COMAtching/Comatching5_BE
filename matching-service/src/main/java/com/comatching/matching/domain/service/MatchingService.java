package com.comatching.matching.domain.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.Hobby;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.dto.MatchingResponse;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.dto.MatchingHistoryResponse;

public interface MatchingService {

	MatchingResponse match(Long memberId, MatchingRequest request);

	Page<MatchingHistoryResponse> getMyMatchingHistory(Long memberId, Pageable pageable);
}
