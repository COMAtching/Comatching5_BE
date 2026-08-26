package com.comatching.common.config;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;

/**
 * open 알림 쿨다운·복구 짝 규칙 검증.
 *
 * 지속 장애 중 브레이커는 open → half-open → open 을 wait-duration(10s)마다
 * 반복한다. 쿨다운이 없으면 장애 5분에 알림 30건이 되고, 억제된 open 에
 * 복구 알림까지 짝지어 보내면 도배가 두 배가 된다. KafkaDltAlertNotifierTest
 * 처럼 시계를 주입해 경계를 결정적으로 확인한다.
 */
@DisplayName("서킷브레이커 open 알림")
class CircuitBreakerAlertNotifierTest {

	private static final Duration COOLDOWN = Duration.ofMinutes(5);
	private static final String BREAKER = "user-service";

	static class TestNotifier extends CircuitBreakerAlertNotifier {
		long now = 1L;
		final List<String> sent = new ArrayList<>();

		TestNotifier(String webhookUrl) {
			super(webhookUrl, "matching-service", COOLDOWN);
		}

		@Override
		protected void deliverAsync(String message) {
			// 실제 구현은 별도 스레드로 넘기지만, 테스트는 동기로 받아 검증한다.
			sent.add(message);
		}

		@Override
		protected long nowMillis() {
			return now;
		}
	}

	private static CircuitBreakerOnStateTransitionEvent event(CircuitBreaker.StateTransition transition) {
		return new CircuitBreakerOnStateTransitionEvent(BREAKER, transition);
	}

	@Test
	@DisplayName("open 전이는 알림을 보내고, 쿨다운 안의 재발은 억제 후 다음 알림에 합산한다")
	void cooldownSuppressesRepeatedOpens() {
		TestNotifier notifier = new TestNotifier("http://webhook.example");

		notifier.onStateTransition(event(CircuitBreaker.StateTransition.CLOSED_TO_OPEN));
		assertThat(notifier.sent).hasSize(1);
		assertThat(notifier.sent.get(0)).contains("OPEN", BREAKER, "matching-service");

		// 쿨다운 안 재발(half-open 에서 다시 실패) 3회 - 억제
		notifier.now += COOLDOWN.toMillis() - 1;
		for (int i = 0; i < 3; i++) {
			notifier.onStateTransition(event(CircuitBreaker.StateTransition.HALF_OPEN_TO_OPEN));
		}
		assertThat(notifier.sent).hasSize(1);

		// 쿨다운이 지나면 다시 보내고, 억제분을 합산 보고한다
		notifier.now += 1;
		notifier.onStateTransition(event(CircuitBreaker.StateTransition.HALF_OPEN_TO_OPEN));
		assertThat(notifier.sent).hasSize(2);
		assertThat(notifier.sent.get(1)).contains("3회");
	}

	@Test
	@DisplayName("복구 알림은 open 알림이 실제로 나간 경우에만 한 번 보낸다")
	void recoveryNotifiesOncePerSentOpenAlert() {
		TestNotifier notifier = new TestNotifier("http://webhook.example");

		// open 알림 없이 복구 전이만 오면(예: 재기동 직후) 보내지 않는다
		notifier.onStateTransition(event(CircuitBreaker.StateTransition.HALF_OPEN_TO_CLOSED));
		assertThat(notifier.sent).isEmpty();

		notifier.onStateTransition(event(CircuitBreaker.StateTransition.CLOSED_TO_OPEN));
		notifier.onStateTransition(event(CircuitBreaker.StateTransition.HALF_OPEN_TO_CLOSED));
		assertThat(notifier.sent).hasSize(2);
		assertThat(notifier.sent.get(1)).contains("복구", BREAKER);

		// 같은 복구가 또 와도(또는 open 알림이 억제된 채 복구되어도) 반복하지 않는다
		notifier.onStateTransition(event(CircuitBreaker.StateTransition.HALF_OPEN_TO_CLOSED));
		assertThat(notifier.sent).hasSize(2);
	}

	@Test
	@DisplayName("open/closed 외의 전이(half-open 진입)는 알리지 않는다")
	void ignoresIntermediateTransitions() {
		TestNotifier notifier = new TestNotifier("http://webhook.example");

		notifier.onStateTransition(event(CircuitBreaker.StateTransition.OPEN_TO_HALF_OPEN));

		assertThat(notifier.sent).isEmpty();
	}

	@Test
	@DisplayName("웹훅 미설정이면 아무것도 보내지 않고 조용히 동작한다")
	void disabledWithoutWebhook() {
		TestNotifier notifier = new TestNotifier("");

		notifier.onStateTransition(event(CircuitBreaker.StateTransition.CLOSED_TO_OPEN));
		notifier.onStateTransition(event(CircuitBreaker.StateTransition.HALF_OPEN_TO_CLOSED));

		assertThat(notifier.sent).isEmpty();
	}
}
