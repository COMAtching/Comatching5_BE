package com.comatching.notification.infra.kafka.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.annotation.KafkaListener;

import com.comatching.common.dto.event.chat.ChatMessageEvent;
import com.comatching.notification.domain.service.fcm.FCMService;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ChatEventConsumerTest {

	@Mock
	private FCMService fcmService;

	@Test
	@DisplayName("채팅 알림 consumer는 JSON listener factory를 사용한다")
	void shouldUseJsonKafkaListenerFactory() throws NoSuchMethodException {
		// when
		KafkaListener listener = ChatEventConsumer.class
			.getMethod("handleChatEvent", ChatMessageEvent.class)
			.getAnnotation(KafkaListener.class);

		// then
		assertThat(listener.containerFactory()).isEqualTo("jsonKafkaListenerContainerFactory");
	}

	@Test
	@DisplayName("채팅 알림 이벤트를 소비하고 FCM 발송을 위임한다")
	void handleChatEvent_logsAndDelegates(CapturedOutput output) {
		// given
		ChatEventConsumer consumer = new ChatEventConsumer(fcmService);
		ChatMessageEvent event = new ChatMessageEvent(
			2L,
			"sender",
			"room-1",
			"hello",
			"2026-05-18T00:00:00",
			"CHAT"
		);

		// when
		consumer.handleChatEvent(event);

		// then
		assertThat(output).contains("chat.notification.consume roomId=room-1 targetUserId=2 type=CHAT");
		then(fcmService).should().sendNotification(event);
	}
}
