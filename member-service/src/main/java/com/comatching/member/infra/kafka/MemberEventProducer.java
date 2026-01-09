package com.comatching.member.infra.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.MemberAuthEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemberEventProducer {

	private final KafkaTemplate<String, String> kafkaTemplate;
	private final ObjectMapper objectMapper;

	private static final String TOPIC_MEMBER_AUTH = "member-auth";

	public void sendWithdrawEvent(Long memberId, String email) {
		MemberAuthEvent event = MemberAuthEvent.builder()
			.memberId(memberId)
			.email(email)
			.type(MemberAuthEvent.EventType.WITHDRAWAL)
			.build();

		sendToKafka(event);
	}

	private void sendToKafka(MemberAuthEvent event) {
		try {
			String message = objectMapper.writeValueAsString(event);
			kafkaTemplate.send(TOPIC_MEMBER_AUTH, message);
			log.info("Sended MemberAuthEvent to Kafka: [{}] memberId={}", event.type(), event.memberId());
		} catch (JsonProcessingException e) {
			log.error("Failed to serialize MemberAuthEvent", e);
		} catch (Exception e) {
			log.error("Failed to send MemberAuthEvent to Kafka", e);
		}
	}
}
