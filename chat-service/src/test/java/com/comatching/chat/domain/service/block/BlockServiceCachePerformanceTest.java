package com.comatching.chat.domain.service.block;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.comatching.chat.domain.entity.UserBlock;
import com.comatching.chat.domain.repository.UserBlockRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("BlockService 캐싱 성능 비교 테스트")
class BlockServiceCachePerformanceTest {

	@Mock
	private UserBlockRepository userBlockRepository;

	@Mock
	private RedisTemplate<String, Object> redisTemplate;

	@Mock
	private ValueOperations<String, Object> valueOperations;

	private BlockServiceImpl blockService;

	private static final Long BLOCKER_USER_ID = 1L;
	private static final int BLOCKED_USER_COUNT = 10;
	private static final int ITERATION_COUNT = 1000;

	// 시뮬레이션용 지연 시간 (실제 환경 근사치)
	private static final long SIMULATED_DB_LATENCY_MS = 10;    // MongoDB: ~10ms
	private static final long SIMULATED_REDIS_LATENCY_MS = 1;  // Redis: ~1ms

	private List<UserBlock> mockBlockedUsers;

	@BeforeEach
	void setUp() {
		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		blockService = new BlockServiceImpl(userBlockRepository, redisTemplate);

		// 차단된 사용자 목록 생성
		mockBlockedUsers = LongStream.rangeClosed(2, BLOCKED_USER_COUNT + 1)
			.mapToObj(id -> UserBlock.builder()
				.blockerUserId(BLOCKER_USER_ID)
				.blockedUserId(id)
				.build())
			.toList();
	}

	@Test
	@DisplayName("캐싱 미적용 시나리오 - 매번 DB 조회")
	void performanceTest_withoutCache() {
		// given: 캐시 미스 시뮬레이션 (항상 null 반환)
		given(valueOperations.get(anyString())).willReturn(null);

		// DB 조회 시 지연 시뮬레이션
		given(userBlockRepository.findByBlockerUserId(BLOCKER_USER_ID))
			.willAnswer(invocation -> {
				simulateLatency(SIMULATED_DB_LATENCY_MS);
				return mockBlockedUsers;
			});

		// when: 1000번 isBlocked 호출
		long startTime = System.currentTimeMillis();

		for (int i = 0; i < ITERATION_COUNT; i++) {
			Long targetUserId = (long) (i % BLOCKED_USER_COUNT) + 2;
			blockService.isBlocked(BLOCKER_USER_ID, targetUserId);
		}

		long endTime = System.currentTimeMillis();
		long totalTime = endTime - startTime;

		// then
		System.out.println("===========================================");
		System.out.println("📊 캐싱 미적용 시나리오 결과");
		System.out.println("===========================================");
		System.out.println("호출 횟수: " + ITERATION_COUNT + "번");
		System.out.println("총 소요 시간: " + totalTime + "ms");
		System.out.println("평균 응답 시간: " + (totalTime / (double) ITERATION_COUNT) + "ms");
		System.out.println("DB 쿼리 횟수: " + ITERATION_COUNT + "번");
		System.out.println("===========================================\n");

		// DB 조회가 매번 발생했는지 검증
		then(userBlockRepository).should(times(ITERATION_COUNT)).findByBlockerUserId(BLOCKER_USER_ID);
	}

	@Test
	@DisplayName("캐싱 적용 시나리오 - 첫 호출만 DB 조회, 이후 캐시 히트")
	void performanceTest_withCache() {
		// given: 첫 호출은 캐시 미스, 이후는 캐시 히트
		Set<Long> cachedBlockedIds = mockBlockedUsers.stream()
			.map(UserBlock::getBlockedUserId)
			.collect(Collectors.toSet());

		// 첫 번째 호출: 캐시 미스 → DB 조회
		// 두 번째 이후: 캐시 히트
		given(valueOperations.get("user:block:" + BLOCKER_USER_ID))
			.willAnswer(invocation -> {
				simulateLatency(SIMULATED_REDIS_LATENCY_MS);
				return null;  // 첫 호출
			})
			.willAnswer(invocation -> {
				simulateLatency(SIMULATED_REDIS_LATENCY_MS);
				return cachedBlockedIds;  // 이후 호출
			});

		given(userBlockRepository.findByBlockerUserId(BLOCKER_USER_ID))
			.willAnswer(invocation -> {
				simulateLatency(SIMULATED_DB_LATENCY_MS);
				return mockBlockedUsers;
			});

		// when: 1000번 isBlocked 호출
		long startTime = System.currentTimeMillis();

		for (int i = 0; i < ITERATION_COUNT; i++) {
			Long targetUserId = (long) (i % BLOCKED_USER_COUNT) + 2;
			blockService.isBlocked(BLOCKER_USER_ID, targetUserId);
		}

		long endTime = System.currentTimeMillis();
		long totalTime = endTime - startTime;

		// then
		System.out.println("===========================================");
		System.out.println("📊 캐싱 적용 시나리오 결과");
		System.out.println("===========================================");
		System.out.println("호출 횟수: " + ITERATION_COUNT + "번");
		System.out.println("총 소요 시간: " + totalTime + "ms");
		System.out.println("평균 응답 시간: " + (totalTime / (double) ITERATION_COUNT) + "ms");
		System.out.println("DB 쿼리 횟수: 1번 (첫 호출만)");
		System.out.println("캐시 히트 횟수: " + (ITERATION_COUNT - 1) + "번");
		System.out.println("===========================================\n");

		// DB 조회는 1번만 발생했는지 검증
		then(userBlockRepository).should(times(1)).findByBlockerUserId(BLOCKER_USER_ID);
	}

	@Test
	@DisplayName("캐싱 적용 vs 미적용 성능 비교")
	void performanceComparison() {
		System.out.println("\n");
		System.out.println("╔═══════════════════════════════════════════════════════════════╗");
		System.out.println("║           BlockService 캐싱 성능 비교 테스트                    ║");
		System.out.println("╠═══════════════════════════════════════════════════════════════╣");
		System.out.println("║  설정값:                                                       ║");
		System.out.println("║  - 호출 횟수: " + ITERATION_COUNT + "번                                          ║");
		System.out.println("║  - 차단된 사용자 수: " + BLOCKED_USER_COUNT + "명                                   ║");
		System.out.println("║  - 시뮬레이션 DB 지연: " + SIMULATED_DB_LATENCY_MS + "ms                                ║");
		System.out.println("║  - 시뮬레이션 Redis 지연: " + SIMULATED_REDIS_LATENCY_MS + "ms                              ║");
		System.out.println("╚═══════════════════════════════════════════════════════════════╝");

		// ========== 캐싱 미적용 테스트 ==========
		given(valueOperations.get(anyString())).willReturn(null);
		given(userBlockRepository.findByBlockerUserId(BLOCKER_USER_ID))
			.willAnswer(invocation -> {
				simulateLatency(SIMULATED_DB_LATENCY_MS);
				return mockBlockedUsers;
			});

		long noCacheStart = System.currentTimeMillis();
		for (int i = 0; i < ITERATION_COUNT; i++) {
			blockService.isBlocked(BLOCKER_USER_ID, 2L);
		}
		long noCacheTime = System.currentTimeMillis() - noCacheStart;

		// Mock 리셋
		reset(valueOperations, userBlockRepository);
		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

		// ========== 캐싱 적용 테스트 ==========
		Set<Long> cachedBlockedIds = mockBlockedUsers.stream()
			.map(UserBlock::getBlockedUserId)
			.collect(Collectors.toSet());

		// 첫 호출만 캐시 미스
		List<Set<Long>> responses = new ArrayList<>();
		responses.add(null);  // 첫 호출: 캐시 미스
		for (int i = 1; i < ITERATION_COUNT; i++) {
			responses.add(cachedBlockedIds);  // 이후: 캐시 히트
		}

		final int[] callCount = {0};
		given(valueOperations.get("user:block:" + BLOCKER_USER_ID))
			.willAnswer(invocation -> {
				simulateLatency(SIMULATED_REDIS_LATENCY_MS);
				if (callCount[0] < responses.size()) {
					return responses.get(callCount[0]++);
				}
				return cachedBlockedIds;
			});

		given(userBlockRepository.findByBlockerUserId(BLOCKER_USER_ID))
			.willAnswer(invocation -> {
				simulateLatency(SIMULATED_DB_LATENCY_MS);
				return mockBlockedUsers;
			});

		long cacheStart = System.currentTimeMillis();
		for (int i = 0; i < ITERATION_COUNT; i++) {
			blockService.isBlocked(BLOCKER_USER_ID, 2L);
		}
		long cacheTime = System.currentTimeMillis() - cacheStart;

		// ========== 결과 출력 ==========
		double improvement = ((double)(noCacheTime - cacheTime) / noCacheTime) * 100;
		double speedup = (double) noCacheTime / cacheTime;

		System.out.println("\n");
		System.out.println("┌───────────────────────────────────────────────────────────────┐");
		System.out.println("│                        📊 성능 비교 결과                        │");
		System.out.println("├───────────────────────────────────────────────────────────────┤");
		System.out.printf("│  ❌ 캐싱 미적용:  %,10d ms  (DB 쿼리 %d번)                 │%n", noCacheTime, ITERATION_COUNT);
		System.out.printf("│  ✅ 캐싱 적용:    %,10d ms  (DB 쿼리 1번)                    │%n", cacheTime);
		System.out.println("├───────────────────────────────────────────────────────────────┤");
		System.out.printf("│  🚀 성능 향상:    %.1f%% 감소                                   │%n", improvement);
		System.out.printf("│  ⚡ 속도 향상:    %.1fx 빠름                                    │%n", speedup);
		System.out.println("└───────────────────────────────────────────────────────────────┘");

		// 검증: 캐싱 적용 시 더 빨라야 함
		assertThat(cacheTime).isLessThan(noCacheTime);
		System.out.println("\n✅ 테스트 통과: 캐싱 적용 시 " + String.format("%.1fx", speedup) + " 성능 향상 확인\n");
	}

	@Test
	@DisplayName("다양한 호출 횟수에서의 성능 비교")
	void performanceComparisonAtDifferentScales() {
		int[] iterationCounts = {100, 500, 1000, 5000};

		System.out.println("\n");
		System.out.println("╔═══════════════════════════════════════════════════════════════════════════╗");
		System.out.println("║              다양한 호출 횟수에서의 캐싱 성능 비교                            ║");
		System.out.println("╠═══════════════════════════════════════════════════════════════════════════╣");
		System.out.println("║  호출 횟수  │  캐싱 미적용  │  캐싱 적용  │  성능 향상  │  속도 향상        ║");
		System.out.println("╠═══════════════════════════════════════════════════════════════════════════╣");

		for (int iterations : iterationCounts) {
			// Mock 리셋
			reset(valueOperations, userBlockRepository);
			lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

			// 캐싱 미적용 테스트
			given(valueOperations.get(anyString())).willReturn(null);
			given(userBlockRepository.findByBlockerUserId(BLOCKER_USER_ID))
				.willAnswer(invocation -> {
					simulateLatency(SIMULATED_DB_LATENCY_MS);
					return mockBlockedUsers;
				});

			long noCacheStart = System.currentTimeMillis();
			for (int i = 0; i < iterations; i++) {
				blockService.isBlocked(BLOCKER_USER_ID, 2L);
			}
			long noCacheTime = System.currentTimeMillis() - noCacheStart;

			// Mock 리셋
			reset(valueOperations, userBlockRepository);
			lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

			// 캐싱 적용 테스트
			Set<Long> cachedBlockedIds = mockBlockedUsers.stream()
				.map(UserBlock::getBlockedUserId)
				.collect(Collectors.toSet());

			final int[] callCount = {0};
			given(valueOperations.get("user:block:" + BLOCKER_USER_ID))
				.willAnswer(invocation -> {
					simulateLatency(SIMULATED_REDIS_LATENCY_MS);
					if (callCount[0]++ == 0) {
						return null;  // 첫 호출: 캐시 미스
					}
					return cachedBlockedIds;  // 이후: 캐시 히트
				});

			given(userBlockRepository.findByBlockerUserId(BLOCKER_USER_ID))
				.willAnswer(invocation -> {
					simulateLatency(SIMULATED_DB_LATENCY_MS);
					return mockBlockedUsers;
				});

			long cacheStart = System.currentTimeMillis();
			for (int i = 0; i < iterations; i++) {
				blockService.isBlocked(BLOCKER_USER_ID, 2L);
			}
			long cacheTime = System.currentTimeMillis() - cacheStart;

			double improvement = ((double)(noCacheTime - cacheTime) / noCacheTime) * 100;
			double speedup = (double) noCacheTime / cacheTime;

			System.out.printf("║  %,6d회   │  %,8d ms  │  %,7d ms  │   %5.1f%%    │    %5.1fx        ║%n",
				iterations, noCacheTime, cacheTime, improvement, speedup);

			assertThat(cacheTime).isLessThan(noCacheTime);
		}

		System.out.println("╚═══════════════════════════════════════════════════════════════════════════╝");
		System.out.println("\n💡 결론: 호출 횟수가 많아질수록 캐싱의 효과가 더욱 커집니다.\n");
	}

	@Test
	@DisplayName("실제 채팅 시나리오 시뮬레이션 - 1분간 활성 채팅")
	void realWorldScenarioSimulation() {
		// 시나리오: 1분간 초당 10개의 메시지가 오가는 활성 채팅방
		int messagesPerSecond = 10;
		int durationSeconds = 60;
		int totalMessages = messagesPerSecond * durationSeconds;

		System.out.println("\n");
		System.out.println("╔═══════════════════════════════════════════════════════════════╗");
		System.out.println("║         🗨️ 실제 채팅 시나리오 시뮬레이션                        ║");
		System.out.println("╠═══════════════════════════════════════════════════════════════╣");
		System.out.println("║  시나리오: 1분간 활성 채팅 (초당 10개 메시지)                    ║");
		System.out.println("║  총 메시지 수: " + totalMessages + "개                                        ║");
		System.out.println("║  각 메시지마다 isBlocked() 호출 발생                            ║");
		System.out.println("╚═══════════════════════════════════════════════════════════════╝");

		// 캐싱 미적용
		reset(valueOperations, userBlockRepository);
		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

		given(valueOperations.get(anyString())).willReturn(null);
		given(userBlockRepository.findByBlockerUserId(BLOCKER_USER_ID))
			.willAnswer(invocation -> {
				simulateLatency(SIMULATED_DB_LATENCY_MS);
				return mockBlockedUsers;
			});

		long noCacheStart = System.currentTimeMillis();
		for (int i = 0; i < totalMessages; i++) {
			blockService.isBlocked(BLOCKER_USER_ID, 2L);
		}
		long noCacheTime = System.currentTimeMillis() - noCacheStart;

		// 캐싱 적용
		reset(valueOperations, userBlockRepository);
		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

		Set<Long> cachedBlockedIds = mockBlockedUsers.stream()
			.map(UserBlock::getBlockedUserId)
			.collect(Collectors.toSet());

		final int[] callCount = {0};
		given(valueOperations.get("user:block:" + BLOCKER_USER_ID))
			.willAnswer(invocation -> {
				simulateLatency(SIMULATED_REDIS_LATENCY_MS);
				if (callCount[0]++ == 0) {
					return null;
				}
				return cachedBlockedIds;
			});

		given(userBlockRepository.findByBlockerUserId(BLOCKER_USER_ID))
			.willAnswer(invocation -> {
				simulateLatency(SIMULATED_DB_LATENCY_MS);
				return mockBlockedUsers;
			});

		long cacheStart = System.currentTimeMillis();
		for (int i = 0; i < totalMessages; i++) {
			blockService.isBlocked(BLOCKER_USER_ID, 2L);
		}
		long cacheTime = System.currentTimeMillis() - cacheStart;

		double savedTime = noCacheTime - cacheTime;
		double speedup = (double) noCacheTime / cacheTime;

		System.out.println("\n");
		System.out.println("┌───────────────────────────────────────────────────────────────┐");
		System.out.println("│                     📊 시뮬레이션 결과                          │");
		System.out.println("├───────────────────────────────────────────────────────────────┤");
		System.out.printf("│  ❌ 캐싱 미적용 총 지연:  %,d ms (%.1f초)                      │%n",
			noCacheTime, noCacheTime / 1000.0);
		System.out.printf("│  ✅ 캐싱 적용 총 지연:    %,d ms (%.1f초)                        │%n",
			cacheTime, cacheTime / 1000.0);
		System.out.println("├───────────────────────────────────────────────────────────────┤");
		System.out.printf("│  ⏱️ 절약된 시간:          %,.0f ms (%.1f초)                      │%n",
			savedTime, savedTime / 1000.0);
		System.out.printf("│  🚀 속도 향상:            %.1fx                                 │%n", speedup);
		System.out.println("├───────────────────────────────────────────────────────────────┤");
		System.out.println("│  💡 의미:                                                      │");
		System.out.printf("│     - 캐싱 미적용 시 DB에 %d번 쿼리 발생                       │%n", totalMessages);
		System.out.println("│     - 캐싱 적용 시 DB에 1번만 쿼리 발생                        │");
		System.out.printf("│     - 1분 채팅 동안 약 %.1f초의 지연 시간 절약                  │%n", savedTime / 1000.0);
		System.out.println("└───────────────────────────────────────────────────────────────┘");

		assertThat(cacheTime).isLessThan(noCacheTime);
	}

	private void simulateLatency(long milliseconds) {
		try {
			Thread.sleep(milliseconds);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
