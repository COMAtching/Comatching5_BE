package com.comatching.common.config;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 서킷브레이커 open 알림.
 *
 * 브레이커가 열리면 서비스는 스스로 버티지만(즉시 503), 원인 서비스를
 * 사람이 고치기 전까지는 복구되지 않는다. 열렸다는 사실을 사람이 모르면
 * 브레이커는 장애를 "견디는" 장치가 아니라 "숨기는" 장치가 된다.
 *
 * Prometheus + Alertmanager 가 아니라 발행 시점 직접 웹훅인 이유는
 * KafkaDltAlertNotifier 와 같다 — 모니터링 스택은 부하 테스트용으로 필요할
 * 때만 띄우는 구성이라 상시 알림의 기반이 못 된다. 웹훅 본문의
 * {"content","text"} 겸용 키 규칙도 같다(Discord/Slack 겸용).
 *
 * 억제 규칙:
 *  - open 알림은 브레이커별 쿨다운을 두고, 억제된 횟수는 다음 알림에 합산해
 *    보고한다. 장애가 이어지면 브레이커는 open → half-open → open 을
 *    wait-duration(10s)마다 반복하므로, 쿨다운이 없으면 웹훅이 도배된다.
 *  - 복구(half-open → closed) 알림은 open 알림이 실제로 나간 경우에만 한 번
 *    보낸다. 억제된 open 에 복구 알림까지 짝지어 보내면 도배가 두 배가 된다.
 *
 * 전송은 별도 스레드에서 한다 — 상태 전이 이벤트는 그 전이를 일으킨 요청
 * 스레드에서 동기로 발화하므로, 웹훅 HTTP(최대 3s)가 사용자 응답을 붙잡으면
 * 안 된다. 전송 실패는 삼킨다.
 */
@Slf4j
public class CircuitBreakerAlertNotifier {

	private static final int HTTP_TIMEOUT_MS = 3_000;

	private final boolean enabled;
	private final String serviceName;
	private final long cooldownMs;
	private final RestClient restClient;
	private final ExecutorService deliveryExecutor;
	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

	private static final class Window {
		volatile long lastSentAt = -1;
		volatile boolean openAlerted = false;
		final AtomicLong suppressed = new AtomicLong();
	}

	public CircuitBreakerAlertNotifier(String webhookUrl, String serviceName, Duration cooldown) {
		this.enabled = webhookUrl != null && !webhookUrl.isBlank();
		this.serviceName = serviceName;
		this.cooldownMs = cooldown.toMillis();

		if (enabled) {
			SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
			requestFactory.setConnectTimeout(HTTP_TIMEOUT_MS);
			requestFactory.setReadTimeout(HTTP_TIMEOUT_MS);
			this.restClient = RestClient.builder()
				.baseUrl(webhookUrl)
				.requestFactory(requestFactory)
				.build();
			this.deliveryExecutor = Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "circuit-breaker-alert");
				thread.setDaemon(true);
				return thread;
			});
		} else {
			this.restClient = null;
			this.deliveryExecutor = null;
			log.info("서킷브레이커 알림 웹훅 미설정(comatching.feign.circuit-breaker-alert-webhook-url)"
				+ " - open 시 로그만 남는다");
		}
	}

	public void onStateTransition(CircuitBreakerOnStateTransitionEvent event) {
		CircuitBreaker.State toState = event.getStateTransition().getToState();
		String breakerName = event.getCircuitBreakerName();

		if (toState == CircuitBreaker.State.OPEN) {
			onOpen(breakerName);
			return;
		}
		if (toState == CircuitBreaker.State.CLOSED) {
			onRecovered(breakerName);
		}
	}

	private void onOpen(String breakerName) {
		// 웹훅과 무관하게 항상 남긴다. 웹훅이 없거나 죽었을 때의 최후 흔적이다.
		log.error("서킷브레이커 OPEN: {} (service={})", breakerName, serviceName);

		if (!enabled) {
			return;
		}

		Window window = windows.computeIfAbsent(breakerName, name -> new Window());
		long suppressedSoFar;
		long now = nowMillis();
		synchronized (window) {
			if (window.lastSentAt >= 0 && now - window.lastSentAt < cooldownMs) {
				window.suppressed.incrementAndGet();
				return;
			}
			suppressedSoFar = window.suppressed.getAndSet(0);
			window.lastSentAt = now;
			window.openAlerted = true;
		}

		StringBuilder message = new StringBuilder()
			.append("🔴 [").append(serviceName).append("] 서킷브레이커 OPEN: ").append(breakerName).append('\n')
			.append(breakerName).append(" 로 가는 내부 호출이 차단되고 있다 (503 응답)");
		if (suppressedSoFar > 0) {
			message.append("\n(직전 쿨다운 동안 open 전이 ").append(suppressedSoFar).append("회 추가 발생)");
		}
		deliverAsync(message.toString());
	}

	private void onRecovered(String breakerName) {
		if (!enabled) {
			return;
		}

		Window window = windows.get(breakerName);
		if (window == null) {
			return;
		}
		synchronized (window) {
			if (!window.openAlerted) {
				return;
			}
			window.openAlerted = false;
		}

		deliverAsync("🟢 [" + serviceName + "] 서킷브레이커 복구: " + breakerName);
	}

	protected void deliverAsync(String message) {
		deliveryExecutor.execute(() -> deliver(message));
	}

	protected void deliver(String message) {
		try {
			restClient.post()
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("content", message, "text", message))
				.retrieve()
				.toBodilessEntity();
		} catch (Exception e) {
			log.warn("서킷브레이커 알림 웹훅 전송 실패 - 호출 흐름에는 영향 없음", e);
		}
	}

	protected long nowMillis() {
		return System.currentTimeMillis();
	}
}
