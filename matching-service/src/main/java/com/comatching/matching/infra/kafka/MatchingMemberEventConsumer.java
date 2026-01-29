package com.comatching.matching.infra.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.member.MemberWithdrawnEvent;
import com.comatching.matching.domain.service.CandidateService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingMemberEventConsumer {

	private final CandidateService candidateService;

	@KafkaListener(topics = "member-withdraw", groupId = "matching-service-group")
	public void handleMemberWithdraw(MemberWithdrawnEvent event) {
		try {
			candidateService.removeCandidate(event.memberId());
		} catch (Exception e) {
			log.error("회원 탈퇴 이벤트 처리 중 오류 발생", e);
		}
	}
}