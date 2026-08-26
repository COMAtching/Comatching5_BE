package com.comatching.matching.infra.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.member.MemberWithdrawnEvent;
import com.comatching.matching.domain.service.CandidateService;
import com.comatching.matching.global.config.KafkaTopicConfig;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MatchingMemberEventConsumer {

	private final CandidateService candidateService;

	// concurrency 는 설정값(상한은 파티션 수, KafkaTopicConfig 참고). 프로듀서가
	// memberId 를 키로 걸어 주므로 같은 회원의 이벤트는 항상 같은 파티션으로 온다.
	@KafkaListener(topics = "member-withdraw", groupId = "matching-service-group",
		concurrency = KafkaTopicConfig.CANDIDATE_LISTENER_CONCURRENCY)
	public void handleMemberWithdraw(MemberWithdrawnEvent event) {
		// 예외를 잡지 않는다. 여기서 삼키면 오프셋이 그대로 커밋되어 재시도도
		// DLT 도 없이 탈퇴가 사라지고, 후보 테이블에 탈퇴 회원이 영구히 남는다.
		// 공통 에러 핸들러(KafkaConsumerConfig)가 재시도 후 DLT 로 보낸다.
		// removeCandidate 는 멱등이라 재시도해도 안전하다.
		candidateService.removeCandidate(event.memberId(), event.withdrawnAt());
	}
}
