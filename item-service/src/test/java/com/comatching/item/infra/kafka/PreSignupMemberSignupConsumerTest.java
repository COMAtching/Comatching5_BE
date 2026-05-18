package com.comatching.item.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.annotation.KafkaListener;

import com.comatching.common.dto.event.member.MemberAuthEvent;
import com.comatching.item.domain.grant.service.PreSignupMatchingTicketGrantService;

@ExtendWith(MockitoExtension.class)
@DisplayName("PreSignupMemberSignupConsumer 테스트")
class PreSignupMemberSignupConsumerTest {

	@InjectMocks
	private PreSignupMemberSignupConsumer consumer;

	@Mock
	private PreSignupMatchingTicketGrantService grantService;

	@Test
	@DisplayName("SIGNUP 이벤트면 매칭권 지급을 위임한다")
	void shouldGrantOnSignupEvent() {
		// given
		MemberAuthEvent event = MemberAuthEvent.builder()
			.memberId(11L)
			.email("user@example.com")
			.nickname("user")
			.type(MemberAuthEvent.EventType.SIGNUP)
			.build();

		// when
		consumer.consumeSignup(event);

		// then
		then(grantService).should().grantMatchingTicket(11L);
	}

	@Test
	@DisplayName("SIGNUP이 아닌 이벤트면 지급하지 않는다")
	void shouldSkipNonSignupEvent() {
		// given
		MemberAuthEvent event = MemberAuthEvent.builder()
			.memberId(11L)
			.email("user@example.com")
			.nickname("user")
			.type(MemberAuthEvent.EventType.WITHDRAWAL)
			.build();

		// when
		consumer.consumeSignup(event);

		// then
		then(grantService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("Kafka listener는 전용 groupId와 latest offset 정책을 사용한다")
	void shouldUseDedicatedKafkaListenerPolicy() throws NoSuchMethodException {
		// when
		var method = PreSignupMemberSignupConsumer.class.getMethod("consumeSignup", MemberAuthEvent.class);

		// then
		KafkaListener listener = method.getAnnotation(KafkaListener.class);
		assertThat(listener).isNotNull();
		assertThat(listener.topics()).containsExactly("member-signup");
		assertThat(listener.groupId()).isEqualTo("item-pre-signup-matching-ticket-grant-group");
		assertThat(listener.properties()).containsExactly("auto.offset.reset=latest");
	}
}
