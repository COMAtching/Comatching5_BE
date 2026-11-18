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

	// 토픽 이름은 "무슨 일이 있었나"(채팅 메시지가 보내졌다)로 짓는다. 누가 받아서
	// 무엇을 할지(알림 발송)는 구독자의 몫이라 이름에 담지 않는다.
	private static final String CHAT_MESSAGE_SENT_TOPIC = "chat-message-sent";

	private final KafkaTemplate<String, Object> kafkaTemplate;

	public void send(ChatMessageEvent event) {
		try {
			kafkaTemplate.send(CHAT_MESSAGE_SENT_TOPIC, event)
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
