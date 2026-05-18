package com.comatching.chat.infra.kafka;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.chat.ChatMessageEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatEventProducer {

	private static final String CHAT_NOTIFICATION_TOPIC = "chat-notification";

	private final KafkaTemplate<String, Object> kafkaTemplate;

	public void send(ChatMessageEvent event) {
		try {
			kafkaTemplate.send(CHAT_NOTIFICATION_TOPIC, event)
				.whenComplete((result, throwable) -> {
					if (throwable != null) {
						log.warn(
							"chat.notification.kafka.send.failure roomId={} targetUserId={} error={}",
							event.roomId(),
							event.targetUserId(),
							throwable.getMessage()
						);
						return;
					}

					RecordMetadata metadata = result.getRecordMetadata();
					log.info(
						"chat.notification.kafka.send.success roomId={} targetUserId={} topic={} partition={} offset={}",
						event.roomId(),
						event.targetUserId(),
						metadata.topic(),
						metadata.partition(),
						metadata.offset()
					);
				});
		} catch (RuntimeException e) {
			log.warn(
				"chat.notification.kafka.send.failure roomId={} targetUserId={} error={}",
				event.roomId(),
				event.targetUserId(),
				e.getMessage()
			);
		}
	}
}
