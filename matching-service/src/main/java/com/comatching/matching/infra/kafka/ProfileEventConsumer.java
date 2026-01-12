package com.comatching.matching.infra.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.repository.candidate.MatchingCandidateRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileEventConsumer {

	private final MatchingCandidateRepository repository;

	@KafkaListener(topics = "profile-updates", groupId = "matching-service-group", containerFactory = "profileUpdateKafkaListenerContainerFactory")
	@Transactional
	public void consumeProfileUpdate(ProfileUpdatedMatchingEvent event) {
		log.info("Kafka Consume: Profile Update for memberId={}", event.memberId());

		repository.findById(event.memberId())
			.ifPresentOrElse(
				candidate -> {
					candidate.syncProfile(
						event.profileId(),
						event.gender(),
						event.mbti(),
						event.major(),
						event.hobbies(),
						event.birthDate(),
						event.isMatchable()
					);
					log.info("Updated MatchingCandidate: memberId={}", event.memberId());
				},
				() -> {
					MatchingCandidate newCandidate = MatchingCandidate.create(
						event.memberId(),
						event.profileId(),
						event.gender(),
						event.mbti(),
						event.major(),
						event.hobbies(),
						event.birthDate(),
						event.isMatchable()
					);
					repository.save(newCandidate);
					log.info("Created New MatchingCandidate: memberId={}", event.memberId());
				}
			);
	}
}
