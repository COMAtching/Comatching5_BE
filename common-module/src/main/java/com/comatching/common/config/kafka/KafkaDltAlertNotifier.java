package com.comatching.common.config.kafka;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * DLT 적재 알림.
 *
 * DLT 는 "사람이 열어 보고 고치는" 저장소인데, 적재 사실을 사람이 모르면
 * 유실을 눈에 보이게 만든 의미가 없다. 특히 profile-updates.DLT 재적재는
 * tombstone TTL(2일) 안에 해야 하므로, 쌓인 걸 이틀 안에 알아채는 것이
 * 재처리 수단보다 먼저다.
 *
 * Prometheus + Alertmanager 경로가 아니라 발행 시점 직접 웹훅인 이유:
 * 현재 모니터링 스택(docker-compose.monitoring.yml)은 부하 테스트용으로
 * 필요할 때만 띄우는 구성이라 상시 알림의 기반이 못 되고, Alertmanager ·
 * kafka-exporter 를 상시 운영에 추가하는 것은 축제 단발성 서비스에 과하다.
 * 발행 시점 알림은 어느 메시지가 왜 실패했는지까지 본문에 실을 수 있다는
 * 덤도 있다.
 *
 * 웹훅 본문은 {"content", "text"} 두 키를 함께 보낸다. Discord 는 content 를,
 * Slack 은 text 를 읽고 서로 모르는 키는 무시하므로 URL 만 바꾸면 둘 다 된다.
 *
 * 같은 토픽의 연속 실패(예: poison pill 뒤 백로그)가 웹훅을 도배하지 않도록
 * 토픽별 쿨다운을 두고, 억제된 건수는 다음 알림에 합산해 보고한다.
 * 전송 실패는 삼킨다 - 알림 장애가 소비 흐름(오프셋 진행)을 막으면 안 된다.
 */
@Slf4j
public class KafkaDltAlertNotifier {

	private static final int HTTP_TIMEOUT_MS = 3_000;
	private static final int EXCEPTION_MESSAGE_MAX_LENGTH = 300;

	private final boolean enabled;
	private final String groupId;
	private final long cooldownMs;
	private final RestClient restClient;
	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

	private static final class Window {
		volatile long lastSentAt = -1;
		final AtomicLong suppressed = new AtomicLong();
	}

	public KafkaDltAlertNotifier(String webhookUrl, String groupId, Duration cooldown) {
		this.enabled = webhookUrl != null && !webhookUrl.isBlank();
		this.groupId = groupId;
		this.cooldownMs = cooldown.toMillis();

		if (enabled) {
			SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
			requestFactory.setConnectTimeout(HTTP_TIMEOUT_MS);
			requestFactory.setReadTimeout(HTTP_TIMEOUT_MS);
			this.restClient = RestClient.builder()
				.baseUrl(webhookUrl)
				.requestFactory(requestFactory)
				.build();
		} else {
			this.restClient = null;
			log.info("DLT 알림 웹훅 미설정(comatching.kafka.dlt-alert-webhook-url) - 적재 시 로그만 남는다");
		}
	}

	/**
	 * DLT 발행이 성공한 직후 호출된다. 발행 실패는 recoverer 예외로 전파되어
	 * 에러 핸들러가 다시 다루므로 여기까지 오지 않는다.
	 */
	public void notifyParked(ConsumerRecord<?, ?> record, Exception exception) {
		// 웹훅과 무관하게 항상 남긴다. 웹훅이 없거나 죽었을 때의 최후 흔적이다.
		log.error("DLT 적재: {}{} (원본 p{}@{}, key={}, group={})",
			record.topic(), KafkaConsumerConfig.DLT_SUFFIX,
			record.partition(), record.offset(), record.key(), groupId, exception);

		if (!enabled) {
			return;
		}

		Window window = windows.computeIfAbsent(record.topic(), topic -> new Window());
		long suppressedSoFar;
		long now = nowMillis();
		synchronized (window) {
			if (window.lastSentAt >= 0 && now - window.lastSentAt < cooldownMs) {
				window.suppressed.incrementAndGet();
				return;
			}
			suppressedSoFar = window.suppressed.getAndSet(0);
			window.lastSentAt = now;
		}

		deliver(buildMessage(record, exception, suppressedSoFar));
	}

	private String buildMessage(ConsumerRecord<?, ?> record, Exception exception, long suppressed) {
		String cause = rootCauseSummary(exception);
		StringBuilder message = new StringBuilder()
			.append("🚨 [").append(groupId).append("] DLT 적재: ")
			.append(record.topic()).append(KafkaConsumerConfig.DLT_SUFFIX).append('\n')
			.append("원본 ").append(record.topic())
			.append(" p").append(record.partition()).append('@').append(record.offset())
			.append(" · key=").append(record.key()).append('\n')
			.append("예외 ").append(cause);
		if (suppressed > 0) {
			message.append("\n(직전 쿨다운 동안 같은 토픽 ").append(suppressed).append("건 추가 적재)");
		}
		return message.toString();
	}

	private String rootCauseSummary(Exception exception) {
		Throwable cause = exception;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		String text = cause.getClass().getSimpleName() + ": " + cause.getMessage();
		return text.length() > EXCEPTION_MESSAGE_MAX_LENGTH
			? text.substring(0, EXCEPTION_MESSAGE_MAX_LENGTH) + "…"
			: text;
	}

	protected void deliver(String message) {
		try {
			restClient.post()
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("content", message, "text", message))
				.retrieve()
				.toBodilessEntity();
		} catch (Exception e) {
			log.warn("DLT 알림 웹훅 전송 실패 - 소비 흐름에는 영향 없음", e);
		}
	}

	protected long nowMillis() {
		return System.currentTimeMillis();
	}
}
