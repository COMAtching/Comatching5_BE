package com.comatching.item.infra.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.member.MemberAuthEvent;
import com.comatching.item.domain.grant.service.PreSignupMatchingTicketGrantService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PreSignupMemberSignupConsumer {

	private final PreSignupMatchingTicketGrantService grantService;

	@KafkaListener(
		topics = "member-signup",
		groupId = "item-pre-signup-matching-ticket-grant-group",
		properties = "auto.offset.reset=latest"
	)
	public void consumeSignup(MemberAuthEvent event) {
		if (event == null) {
			log.warn("member-signup event is null. skip pre-signup grant.");
			return;
		}
		if (event.type() != MemberAuthEvent.EventType.SIGNUP) {
			log.debug("member-signup event type is not SIGNUP. memberId={}, type={}", event.memberId(), event.type());
			return;
		}

		grantService.grantMatchingTicket(event.memberId());
	}
}
