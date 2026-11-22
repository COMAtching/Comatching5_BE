package com.comatching.chat.infra.kafka;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.comatching.common.dto.event.chat.ChatMessageEvent;
import com.comatching.common.config.kafka.KafkaEventRoute;

class ChatEventProducerTest {

	private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
	private final ChatEventProducer chatEventProducer = new ChatEventProducer(kafkaTemplate);

	@Test
	@DisplayName("채팅방 ID를 key로 발행하고 Kafka의 ACK를 워커로 전달한다")
	void send_exposesKafkaAcknowledgement() {
		// given
		ChatMessageEvent event = event();
		SendResult<String, Object> sendResult = mock(SendResult.class);
		given(kafkaTemplate.send(KafkaEventRoute.CHAT_NOTIFICATION.sourceTopic(), event.roomId(), event))
			.willReturn(CompletableFuture.completedFuture(sendResult));

		// when
		assertThat(chatEventProducer.send(event)).isCompletedWithValue(sendResult);

		// then
		then(kafkaTemplate).should().send(KafkaEventRoute.CHAT_NOTIFICATION.sourceTopic(), event.roomId(), event);
	}

	@Test
	@DisplayName("Kafka 전송 실패를 워커로 전달한다")
	void send_exposesKafkaFailure() {
		// given
		ChatMessageEvent event = event();
		given(kafkaTemplate.send(KafkaEventRoute.CHAT_NOTIFICATION.sourceTopic(), event.roomId(), event))
			.willReturn(CompletableFuture.failedFuture(new RuntimeException("kafka down")));

		// when
		assertThat(chatEventProducer.send(event)).isCompletedExceptionally();

		// then
		then(kafkaTemplate).should().send(KafkaEventRoute.CHAT_NOTIFICATION.sourceTopic(), event.roomId(), event);
	}

	private ChatMessageEvent event() {
		return new ChatMessageEvent(
			2L,
			"sender",
			"room-1",
			"hello",
			"2026-05-18T00:00:00",
			"CHAT", "message-1"
		);
	}
}
