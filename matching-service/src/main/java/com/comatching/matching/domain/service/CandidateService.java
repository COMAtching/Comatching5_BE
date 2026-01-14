package com.comatching.matching.domain.service;

import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;

public interface CandidateService {

	void removeCandidate(Long memberId);

	void upsertCandidate(ProfileUpdatedMatchingEvent event);
}
