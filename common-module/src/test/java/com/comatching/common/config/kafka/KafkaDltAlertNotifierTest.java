package com.comatching.common.config.kafka;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 쿨다운 억제 로직 검증.
 *
 * 같은 경로의 반복 알림은 제한하고 다른 경로는 즉시 알리는지 시계를 주입해 확인한다.
 */
@DisplayName("DLT 적재 알림 쿨다운")
class KafkaDltAlertNotifierTest {

	private static final Duration COOLDOWN = Duration.ofMinutes(5);

	static class TestNotifier extends KafkaDltAlertNotifier {
		long now = 1L;
		final List<String> sent = new ArrayList<>();

		TestNotifier(String webhookUrl) {
			super(webhookUrl, COOLDOWN);
		}

		@Override
		protected long nowMillis() {
			return now;
		}

		@Override
		protected void deliver(String message) {
			sent.add(message);
		}
	}

	private ConsumerRecord<Object, Object> record(String topic) {
		return new ConsumerRecord<>(topic, 0, 0L, "42", "payload");
	}

	@Test
	@DisplayName("첫 적재는 즉시 알리고, 쿨다운 내 같은 토픽 적재는 억제된다")
	void suppressesWithinCooldown() {
		TestNotifier notifier = new TestNotifier("http://webhook");

		notifier.notifyParked(record("member-withdraw"), new IllegalStateException("boom"), KafkaEventRoute.CANDIDATE_WITHDRAW);
		notifier.notifyParked(record("member-withdraw"), new IllegalStateException("boom"), KafkaEventRoute.CANDIDATE_WITHDRAW);

		assertThat(notifier.sent).hasSize(1);
		assertThat(notifier.sent.get(0)).contains(KafkaEventRoute.CANDIDATE_WITHDRAW.dltTopic()).contains("key=42");
	}

	@Test
	@DisplayName("쿨다운이 지나면 다시 알리고, 억제됐던 건수를 함께 보고한다")
	void reportsSuppressedCountAfterCooldown() {
		TestNotifier notifier = new TestNotifier("http://webhook");

		notifier.notifyParked(record("member-withdraw"), new IllegalStateException("boom"), KafkaEventRoute.CANDIDATE_WITHDRAW);
		notifier.notifyParked(record("member-withdraw"), new IllegalStateException("boom"), KafkaEventRoute.CANDIDATE_WITHDRAW);
		notifier.notifyParked(record("member-withdraw"), new IllegalStateException("boom"), KafkaEventRoute.CANDIDATE_WITHDRAW);

		notifier.now += COOLDOWN.toMillis();
		notifier.notifyParked(record("member-withdraw"), new IllegalStateException("boom"), KafkaEventRoute.CANDIDATE_WITHDRAW);

		assertThat(notifier.sent).hasSize(2);
		assertThat(notifier.sent.get(1)).contains("2건 추가 적재");
	}

	@Test
	@DisplayName("같은 원본 토픽이라도 소비 그룹이 다르면 알림을 억제하지 않는다")
	void cooldownIsPerGroupAndSource() {
		TestNotifier notifier = new TestNotifier("http://webhook");

		notifier.notifyParked(record("member-withdraw"), new IllegalStateException("boom"), KafkaEventRoute.CANDIDATE_WITHDRAW);
		notifier.notifyParked(record("member-withdraw"), new IllegalStateException("boom"), KafkaEventRoute.WITHDRAW_MAIL);

		assertThat(notifier.sent).hasSize(2);
	}

	@Test
	@DisplayName("웹훅 URL 이 비어 있으면 전송하지 않는다")
	void disabledWhenUrlBlank() {
		TestNotifier notifier = new TestNotifier("");

		notifier.notifyParked(record("member-withdraw"), new IllegalStateException("boom"), KafkaEventRoute.CANDIDATE_WITHDRAW);

		assertThat(notifier.sent).isEmpty();
	}
    @Test
    @DisplayName("원본과 replay의 실패는 같은 DLT 쿨다운을 공유한다")
    void originalAndReplayShareCooldown() {
        TestNotifier notifier = new TestNotifier("http://webhook");
        KafkaEventRoute route = KafkaEventRoute.CANDIDATE_WITHDRAW;
        notifier.notifyParked(record(route.sourceTopic()), new IllegalStateException("original"), route);
        notifier.notifyParked(record(route.replayTopic()), new IllegalStateException("replay"), route);
        assertThat(notifier.sent).hasSize(1);
        assertThat(notifier.sent.get(0)).contains(route.groupId(), route.dltTopic());
    }
}
