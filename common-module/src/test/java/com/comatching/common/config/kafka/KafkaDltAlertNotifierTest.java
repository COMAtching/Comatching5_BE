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
 * 알림이 안 가는 것과 도배되는 것 모두 사고다: 안 가면 tombstone TTL(2일)
 * 안에 재처리할 기회를 놓치고, 도배되면(poison pill 뒤 백로그) 웹훅 rate
 * limit 에 걸려 정작 다른 토픽의 알림까지 막힌다. 시계를 주입해 경계를
 * 결정적으로 확인한다.
 */
@DisplayName("DLT 적재 알림 쿨다운")
class KafkaDltAlertNotifierTest {

	private static final Duration COOLDOWN = Duration.ofMinutes(5);

	static class TestNotifier extends KafkaDltAlertNotifier {
		long now = 1L;
		final List<String> sent = new ArrayList<>();

		TestNotifier(String webhookUrl) {
			super(webhookUrl, "test-group", COOLDOWN);
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

		notifier.notifyParked(record("topic-a"), new IllegalStateException("boom"));
		notifier.notifyParked(record("topic-a"), new IllegalStateException("boom"));

		assertThat(notifier.sent).hasSize(1);
		assertThat(notifier.sent.get(0)).contains("topic-a.DLT").contains("key=42");
	}

	@Test
	@DisplayName("쿨다운이 지나면 다시 알리고, 억제됐던 건수를 함께 보고한다")
	void reportsSuppressedCountAfterCooldown() {
		TestNotifier notifier = new TestNotifier("http://webhook");

		notifier.notifyParked(record("topic-a"), new IllegalStateException("boom"));
		notifier.notifyParked(record("topic-a"), new IllegalStateException("boom"));
		notifier.notifyParked(record("topic-a"), new IllegalStateException("boom"));

		notifier.now += COOLDOWN.toMillis();
		notifier.notifyParked(record("topic-a"), new IllegalStateException("boom"));

		assertThat(notifier.sent).hasSize(2);
		assertThat(notifier.sent.get(1)).contains("2건 추가 적재");
	}

	@Test
	@DisplayName("쿨다운은 토픽별로 독립이다 - 한 토픽의 폭주가 다른 토픽 알림을 막지 않는다")
	void cooldownIsPerTopic() {
		TestNotifier notifier = new TestNotifier("http://webhook");

		notifier.notifyParked(record("topic-a"), new IllegalStateException("boom"));
		notifier.notifyParked(record("topic-b"), new IllegalStateException("boom"));

		assertThat(notifier.sent).hasSize(2);
	}

	@Test
	@DisplayName("웹훅 URL 이 비어 있으면 전송하지 않는다")
	void disabledWhenUrlBlank() {
		TestNotifier notifier = new TestNotifier("");

		notifier.notifyParked(record("topic-a"), new IllegalStateException("boom"));

		assertThat(notifier.sent).isEmpty();
	}
}
