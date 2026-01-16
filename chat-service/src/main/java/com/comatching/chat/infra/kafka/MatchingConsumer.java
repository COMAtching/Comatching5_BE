package com.comatching.chat.infra.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.comatching.chat.domain.service.chatroom.ChatRoomService;
import com.comatching.common.dto.event.matching.MatchingSuccessEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingConsumer {

	private final ChatRoomService chatRoomService;

	@KafkaListener(topics = "matching-success-topic", groupId = "chat-service-group")
	public void consumeMatchingSuccess(MatchingSuccessEvent event) {
		log.info("Kafka Event Received: {}", event);

		chatRoomService.createChatRoom(event);
	}
}
