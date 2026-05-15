package com.comatching.matching.infra.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.matching.MatchingSuccessEvent;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class MatchingEventProducer {

	private final KafkaTemplate<String, Object> kafkaTemplate;

	private static final String TOPIC = "matching-success-topic";

	public MatchingEventProducer(@Qualifier("jsonKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate) {
		this.kafkaTemplate = kafkaTemplate;
	}

	public void sendMatchingSuccess(MatchingSuccessEvent event) {
		kafkaTemplate.send(TOPIC, event)
			.whenComplete((result, ex) -> {
				if (ex != null) {
					log.error("Failed to publish matching success event. matchingId={}", event.matchingId(), ex);
					return;
				}
				log.info(
					"Published matching success event. matchingId={}, initiatorUserId={}, targetUserId={}",
					event.matchingId(),
					event.initiatorUserId(),
					event.targetUserId()
				);
			});
	}
}
