package com.comatching.matching.domain.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.repository.candidate.MatchingCandidateRepository;
import com.comatching.matching.infra.client.MemberClient;

@Disabled("통합 테스트 환경 설정 필요 - Redis/Kafka 실행 후 활성화")
@SpringBootTest
class MatchingConcurrencyTest {

	@Autowired
	private MatchingService matchingService;

	@Autowired
	private MatchingCandidateRepository candidateRepository;

	@MockitoBean
	private MemberClient memberClient;

	@Test
	void matchingConcurrencyTest() throws InterruptedException {
		// given
		int threadCount = 2;
		// 멀티스레드 환경을 만들기 위한 실행기 (32개 스레드 풀)
		ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
		// 모든 스레드가 준비될 때까지 기다렸다가 동시에 '땅!' 출발시키기 위한 장치
		CountDownLatch latch = new CountDownLatch(threadCount);

		AtomicInteger successCount = new AtomicInteger();
		AtomicInteger failCount = new AtomicInteger();

		Long memberId = 1L; // 테스트할 유저 ID

		MatchingCandidate partner = MatchingCandidate.create(
			2L, 20L, Gender.FEMALE, "ISTJ", "디자인학과", ContactFrequency.FREQUENT,
			new ArrayList<>(Collections.singletonList(HobbyCategory.SPORTS)), LocalDate.of(2000, 11, 11), true
		);
		candidateRepository.save(partner);

		MatchingRequest request = new MatchingRequest(
			null, "IS", HobbyCategory.SPORTS, ContactFrequency.FREQUENT, false, null
		);

		given(memberClient.getProfile(anyLong()))
			.willReturn(ProfileResponse.builder()
				.memberId(memberId)
				.gender(Gender.MALE)
				.mbti("ISTJ")
				.major("컴퓨터공학과")
				.birthDate(LocalDate.of(2000, 1, 1))
				.build());

		// when
		for (int i = 0; i < threadCount; i++) {
			executorService.submit(() -> {
				try {
					// 매칭 요청 실행
					matchingService.match(memberId, request);
					// 예외가 안 나면 성공 카운트 증가
					successCount.getAndIncrement();
				} catch (Exception e) {
					// 락 획득 실패 등으로 예외가 나면 실패 카운트 증가
					System.out.println("매칭 실패: " + e.getMessage());
					failCount.getAndIncrement();
				} finally {
					latch.countDown(); // 작업 끝났음을 알림
				}
			});
		}

		latch.await(); // 모든 스레드가 끝날 때까지 대기

		// then
		System.out.println("=== 테스트 결과 ===");
		System.out.println("성공 횟수: " + successCount.get());
		System.out.println("실패 횟수: " + failCount.get());

		// 검증: 성공은 무조건 1번이어야 함 (락이 제대로 동작했다면)
		assertThat(successCount.get()).isEqualTo(1);
	}

}