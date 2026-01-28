package com.comatching.matching.domain.component;

import com.comatching.common.domain.vo.KoreanAge;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.entity.MatchingCandidate;

public interface MatchingScoreCalculator {

	int calculate(MatchingCandidate candidate, MatchingRequest request, KoreanAge myAge);
}
