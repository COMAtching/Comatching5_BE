package com.comatching.notification.infra.kafka.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.annotation.KafkaListener;

import com.comatching.common.dto.event.chat.ChatMessageEvent;
import com.comatching.notification.domain.service.fcm.FCMService;
import com.comatching.notification.domain.delivery.NotificationDeliveryService;
import com.comatching.notification.domain.service.fcm.FcmDltException;
import com.comatching.notification.infra.client.ChatPushEligibilityClient;

@ExtendWith(MockitoExtension.class)
class ChatEventConsumerTest {

	@Mock
	private FCMService fcmService;
	@Mock private NotificationDeliveryService deliveries;
	@Mock private ChatPushEligibilityClient eligibility;

	@Test
	@DisplayName("채팅 알림 consumer는 기본 listener factory를 선택한다")
	void shouldUseDefaultKafkaListenerFactory() throws NoSuchMethodException {
		// when
		KafkaListener listener = ChatEventConsumer.class
			.getMethod("handleChatEvent", ChatMessageEvent.class)
			.getAnnotation(KafkaListener.class);

		// then
		assertThat(listener.containerFactory()).isEmpty();
	}

	@Test
	@DisplayName("메시지 ID 없는 구형 이벤트를 원장 선점 전에 DLT 대상으로 분류한다")
	void handleChatEvent_parksLegacyRecord() {
		// given
		ChatEventConsumer consumer = new ChatEventConsumer(fcmService, deliveries, eligibility);
		ChatMessageEvent event = new ChatMessageEvent(
			2L,
			"sender",
			"room-1",
			"hello",
			"2026-05-18T00:00:00",
			"CHAT", null
		);

		assertThatThrownBy(() -> consumer.handleChatEvent(event)).isInstanceOf(FcmDltException.class);
		verifyNoInteractions(deliveries, eligibility, fcmService);
	}
}
