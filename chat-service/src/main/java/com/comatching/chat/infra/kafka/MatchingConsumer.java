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

	@KafkaListener(
		topics = "matching-success-topic",
		groupId = "chat-service-group",
		containerFactory = "jsonKafkaListenerContainerFactory"
	)
	public void consumeMatchingSuccess(MatchingSuccessEvent event) {
		try {
			log.info(
				"Received matching success event. matchingId={}, initiatorUserId={}, targetUserId={}",
				event.matchingId(),
				event.initiatorUserId(),
				event.targetUserId()
			);
			chatRoomService.createChatRoom(event);
		} catch (Exception e) {
			log.warn("Failed to handle matching success event. matchingId={}", event.matchingId(), e);
			throw e;
		}
	}
}
