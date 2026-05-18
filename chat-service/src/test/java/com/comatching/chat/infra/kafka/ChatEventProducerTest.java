package com.comatching.chat.infra.kafka;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.comatching.common.dto.event.chat.ChatMessageEvent;

@ExtendWith(OutputCaptureExtension.class)
class ChatEventProducerTest {

	private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
	private final ChatEventProducer chatEventProducer = new ChatEventProducer(kafkaTemplate);

	@Test
	@DisplayName("채팅 알림 Kafka 발행 성공 로그를 남긴다")
	void send_logsKafkaSuccess(CapturedOutput output) {
		// given
		ChatMessageEvent event = event();
		SendResult<String, Object> sendResult = mock(SendResult.class);
		RecordMetadata metadata = mock(RecordMetadata.class);
		given(sendResult.getRecordMetadata()).willReturn(metadata);
		given(metadata.topic()).willReturn("chat-notification");
		given(metadata.partition()).willReturn(1);
		given(metadata.offset()).willReturn(10L);
		given(kafkaTemplate.send("chat-notification", event))
			.willReturn(CompletableFuture.completedFuture(sendResult));

		// when
		chatEventProducer.send(event);

		// then
		assertThat(output).contains("chat.notification.kafka.send.success roomId=room-1 targetUserId=2");
		then(kafkaTemplate).should().send("chat-notification", event);
	}

	@Test
	@DisplayName("채팅 알림 Kafka 발행 실패 로그를 남긴다")
	void send_logsKafkaFailure(CapturedOutput output) {
		// given
		ChatMessageEvent event = event();
		given(kafkaTemplate.send("chat-notification", event))
			.willReturn(CompletableFuture.failedFuture(new RuntimeException("kafka down")));

		// when
		chatEventProducer.send(event);

		// then
		assertThat(output).contains("chat.notification.kafka.send.failure roomId=room-1 targetUserId=2");
		then(kafkaTemplate).should().send("chat-notification", event);
	}

	private ChatMessageEvent event() {
		return new ChatMessageEvent(
			2L,
			"sender",
			"room-1",
			"hello",
			"2026-05-18T00:00:00",
			"CHAT"
		);
	}
}
