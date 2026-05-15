package com.comatching.chat.infra.kafka;

import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.annotation.KafkaListener;

import com.comatching.chat.domain.service.chatroom.ChatRoomService;
import com.comatching.common.dto.event.matching.MatchingSuccessEvent;

@ExtendWith(MockitoExtension.class)
@DisplayName("MatchingConsumer 테스트")
class MatchingConsumerTest {

	@InjectMocks
	private MatchingConsumer matchingConsumer;

	@Mock
	private ChatRoomService chatRoomService;

	@Test
	@DisplayName("matching success event 수신 시 채팅방 생성을 위임한다")
	void shouldCreateChatRoomOnMatchingSuccessEvent() {
		// given
		MatchingSuccessEvent event = new MatchingSuccessEvent(1L, 10L, 20L);

		// when
		matchingConsumer.consumeMatchingSuccess(event);

		// then
		then(chatRoomService).should().createChatRoom(event);
	}

	@Test
	@DisplayName("matching success consumer는 JSON listener factory를 사용한다")
	void shouldUseJsonKafkaListenerFactory() throws NoSuchMethodException {
		KafkaListener listener = MatchingConsumer.class
			.getMethod("consumeMatchingSuccess", MatchingSuccessEvent.class)
			.getAnnotation(KafkaListener.class);

		org.assertj.core.api.Assertions.assertThat(listener.containerFactory())
			.isEqualTo("jsonKafkaListenerContainerFactory");
	}
}
