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
 * 탈퇴 시각을 기준으로 표식을 기본 2일 동안 보관한다. 표식이 있는 동안에는
 * 뒤늦게 도착한 프로필 갱신으로 후보가 다시 생성되는 것을 막는다.
 * 회원 outbox의 발행 재시도와 그룹별 DLT의 replay는 원본 토픽의 보존 기간보다
 * 늦어질 수 있다. 표식이 사라진 뒤 후보를 다시 만들 때에는 회원 서비스의 현재 상태를 확인한다.
 *
 * 다중 인스턴스에서 동시에 돌아도 벌크 삭제는 멱등이라 분산 락이 필요 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawnMemberCleanupScheduler {

	private final WithdrawnMemberRepository withdrawnMemberRepository;

	@Value("${matching.tombstone.retention-days:2}")
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
