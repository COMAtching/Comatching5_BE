package com.comatching.matching.domain.service;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.matching.domain.repository.candidate.WithdrawnMemberRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 탈퇴 tombstone TTL 정리 배치.
 *
 * tombstone 은 늦게 도착한 profile-updates 이벤트가 탈퇴 회원을 부활시키는
 * 것을 막는 가드라서, 늦은 이벤트가 더 이상 올 수 없는 시점 이후에는 지워도
 * 안전하다. 그 상한은 profile-updates 토픽의 retention(브로커 기본 7일) —
 * 그보다 오래된 이벤트는 토픽에서 이미 소거되어 도착 자체가 불가능하다.
 * 기본 보존 14일은 retention 의 2배로, 컨슈머 랙과 DLT 재처리 지연까지
 * 감안한 여유다. profile-updates.DLT 재적재(re-drive)는 반드시 이 보존
 * 기간 안에 해야 한다 — 그 뒤에 재적재하면 tombstone 이 이미 지워져
 * 탈퇴 회원이 부활할 수 있다.
 *
 * 지우지 않고 영구히 쌓으면 저장 공간보다 재가입이 문제다: 같은 memberId 로
 * 다시 가입한 회원의 프로필 이벤트가 tombstone 에 걸러져 영원히 매칭에서
 * 제외된다. 이 배치가 그 차단을 TTL 로 한정한다.
 *
 * 다중 인스턴스에서 동시에 돌아도 벌크 삭제는 멱등이라 분산 락이 필요 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawnMemberCleanupScheduler {

	private final WithdrawnMemberRepository withdrawnMemberRepository;

	@Value("${matching.tombstone.retention-days:14}")
	private long retentionDays;

	@Scheduled(cron = "${matching.tombstone.cleanup-cron:0 0 4 * * *}")
	@Transactional
	public void purgeExpiredTombstones() {
		LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
		int deleted = withdrawnMemberRepository.deleteAllByWithdrawnAtBefore(cutoff);
		if (deleted > 0) {
			log.info("[WithdrawnMemberCleanupScheduler] TTL 경과 tombstone {}건 삭제 (cutoff={})", deleted, cutoff);
		}
	}
}
