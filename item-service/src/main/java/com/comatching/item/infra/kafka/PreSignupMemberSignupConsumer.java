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

	// latest 는 의도된 설정이다. 이 그룹을 처음 배포할 때 보존 기간(7일) 안의 기존
	// 가입자에게 매칭권을 소급 지급하지 않으려고 "지금부터" 읽게 했다.
	// 커밋 오프셋이 생긴 뒤에는 이 값이 쓰이지 않는다. 다시 쓰이는 때는 그룹 이름을
	// 바꾸거나 member-signup 파티션을 늘릴 때인데, 그때는 그 사이 가입분을 건너뛰므로
	// 이 설정을 함께 검토할 것.
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
