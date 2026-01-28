package com.comatching.matching.domain.service;

import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.dto.MatchingResponse;

public interface MatchingService {

	MatchingResponse match(Long memberId, MatchingRequest request);
}
