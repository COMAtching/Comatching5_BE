package com.comatching.matching.domain.component;

import com.comatching.common.domain.vo.KoreanAge;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.entity.MatchingCandidate;

@FunctionalInterface
public interface ImportantConditionChecker {

	boolean check(MatchingCandidate candidate, MatchingRequest request, KoreanAge myAge);
}
