package com.comatching.matching.infra.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.member.MemberWithdrawnEvent;
import com.comatching.matching.domain.service.CandidateService;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingMemberEventConsumer {

	private final CandidateService candidateService;
	private final ObjectMapper objectMapper;

	@KafkaListener(topics = "member-withdraw", groupId = "matching-service-group")
	public void handleMemberWithdraw(String message) {
		try {

			MemberWithdrawnEvent event = objectMapper.readValue(message, MemberWithdrawnEvent.class);
			log.info("회원 탈퇴 이벤트 수신: memberId={}", event.memberId());

			candidateService.removeCandidate(event.memberId());

		} catch (Exception e) {
			log.error("회원 탈퇴 이벤트 처리 중 오류 발생: {}", message, e);
			// 필요 시 Dead Letter Queue(DLQ)로 보내거나 재시도 로직 추가
		}
	}
}