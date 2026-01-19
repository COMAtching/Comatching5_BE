package com.comatching.matching.infra.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.matching.MatchingSuccessEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingEventProducer {

	private final KafkaTemplate<String, Object> kafkaTemplate;

	private static final String TOPIC = "matching-success-topic";

	public void sendMatchingSuccess(MatchingSuccessEvent event) {
		kafkaTemplate.send(TOPIC, event);
	}
}
