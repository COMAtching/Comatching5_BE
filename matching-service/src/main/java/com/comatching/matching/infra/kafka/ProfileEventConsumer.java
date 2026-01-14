package com.comatching.matching.infra.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.matching.domain.service.CandidateService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileEventConsumer {

	private final CandidateService candidateService;

	@KafkaListener(topics = "profile-updates", groupId = "matching-service-group", containerFactory = "profileUpdateKafkaListenerContainerFactory")
	public void consumeProfileUpdate(ProfileUpdatedMatchingEvent event) {

		log.info("Kafka Consume: Profile Update Event Payload = {}", event);
		log.info("Kafka Consume: Profile Update for memberId={}", event.memberId());

		candidateService.upsertCandidate(event);
	}
}
