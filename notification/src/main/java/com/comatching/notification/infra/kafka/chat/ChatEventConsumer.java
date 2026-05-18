package com.comatching.notification.infra.kafka.chat;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.chat.ChatMessageEvent;
import com.comatching.notification.domain.service.fcm.FCMService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatEventConsumer {

	private final FCMService fcmService;

	@KafkaListener(
		topics = "chat-notification",
		groupId = "notification-group",
		containerFactory = "jsonKafkaListenerContainerFactory"
	)
	public void handleChatEvent(ChatMessageEvent event) {
		log.info(
			"chat.notification.consume roomId={} targetUserId={} type={}",
			event.roomId(),
			event.targetUserId(),
			event.type()
		);
		fcmService.sendNotification(event);
	}
}
