package com.comatching.matching.infra.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.member.MemberWithdrawnEvent;
import com.comatching.matching.domain.service.CandidateService;
import com.comatching.matching.global.config.KafkaTopicConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingMemberEventConsumer {

	private final CandidateService candidateService;

	// concurrency 는 파티션 수(KafkaTopicConfig)와 묶여 있다. 프로듀서가 memberId 를
	// 키로 걸어 주므로 같은 회원의 이벤트는 항상 같은 파티션 = 같은 스레드로 온다.
	@KafkaListener(topics = "member-withdraw", groupId = "matching-service-group",
		concurrency = KafkaTopicConfig.CANDIDATE_LISTENER_CONCURRENCY)
	public void handleMemberWithdraw(MemberWithdrawnEvent event) {
		try {
			candidateService.removeCandidate(event.memberId(), event.withdrawnAt());
		} catch (Exception e) {
			log.error("회원 탈퇴 이벤트 처리 중 오류 발생", e);
		}
	}
}