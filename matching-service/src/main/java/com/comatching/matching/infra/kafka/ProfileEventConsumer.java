package com.comatching.matching.infra.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.matching.domain.service.CandidateService;
import com.comatching.matching.global.config.KafkaTopicConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileEventConsumer {

	private final CandidateService candidateService;

	@KafkaListener(topics = "profile-updates", groupId = "matching-service-group",
		concurrency = KafkaTopicConfig.CANDIDATE_LISTENER_CONCURRENCY)
	public void consumeProfileUpdate(ProfileUpdatedMatchingEvent event) {

		candidateService.upsertCandidate(event);
	}
}
