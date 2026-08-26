package com.comatching.matching.domain.service;

import java.time.LocalDateTime;

import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;

public interface CandidateService {

	void removeCandidate(Long memberId, LocalDateTime withdrawnAt);

	void upsertCandidate(ProfileUpdatedMatchingEvent event);
}
