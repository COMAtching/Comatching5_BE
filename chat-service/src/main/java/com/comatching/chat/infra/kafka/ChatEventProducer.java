package com.comatching.chat.infra.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.chat.ChatMessageEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatEventProducer {

	private final KafkaTemplate<String, Object> kafkaTemplate;

	public void send(ChatMessageEvent event) {
		kafkaTemplate.send("chat-notification", event);
	}
}
