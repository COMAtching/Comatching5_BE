package com.comatching.common.config;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;

import com.sun.net.httpserver.HttpServer;

import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Feign + 서킷브레이커 체인이 feign-resilience.yml 대로 실제 동작하는지 검증.
 *
 * 설정 바인딩만 확인해서는(FeignResilienceConfigTest) 잡히지 않는 것들을
 * 실제 HTTP 서버(JDK 내장, 루프백)를 상대로 본다:
 *  1) 브레이커가 메서드가 아닌 Feign 클라이언트(name) 단위로 만들어지고
 *     yml 의 공통값이 그 브레이커에 실제로 들어간다
 *  2) 5xx 가 쌓이면 브레이커가 열려 이후 호출은 서버에 닿지 않는다
 *  3) 4xx 는 실패로 세지 않는다 — 비즈니스 에러가 브레이커를 열면 안 된다
 *  4) Spring Cloud 기본 TimeLimiter(1s)가 개입하지 않는다 — 켜져 있다면
 *     read-timeout(3s) 안쪽의 1s 초과 호출이 잘려나간다
 *  5) read-timeout 초과는 RetryableException 으로 터지고 실패로 집계된다
 *  6) 호출부에는 래퍼(NoFallbackAvailableException)가 아니라 원본 예외가
 *     도달한다 — 아래의 모든 예외 타입 단언이 곧 언랩퍼
 *     (FeignCircuitBreakerExceptionUnwrapper) 검증이다
 *
 * 브로커·DB 없이 루프백 소켓만 쓰므로 단위 테스트 태스크에서 돈다.
 */
@SpringBootTest(
	classes = FeignCircuitBreakerTest.App.class,
	webEnvironment = SpringBootTest.WebEnvironment.NONE,
	properties = {
		// 운영에 나가는 파일 그대로를 물려 테스트한다
		"spring.config.import=classpath:feign-resilience.yml",
		// 타임아웃 검증용 클라이언트만 read 를 짧게 줄인다 (기본 3s 를 기다리지 않도록)
		"spring.cloud.openfeign.client.config.cb-timeout.connect-timeout=1000",
		"spring.cloud.openfeign.client.config.cb-timeout.read-timeout=500"
	}
)
@DisplayName("Feign 서킷브레이커 동작")
class FeignCircuitBreakerTest {

	private static final HttpServer SERVER;
	private static final AtomicInteger FAIL_HITS = new AtomicInteger();

	private static final int SLOW_DELAY_MILLIS = 1500;
	private static final String SLOW_BODY = "slow-ok";

	static {
		try {
			SERVER = HttpServer.create(new InetSocketAddress(0), 0);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		SERVER.createContext("/fail", exchange -> {
			FAIL_HITS.incrementAndGet();
			respond(exchange, 500, "boom");
		});
		SERVER.createContext("/notfound", exchange -> respond(exchange, 404, "no such thing"));
		SERVER.createContext("/slow", exchange -> {
			try {
				Thread.sleep(SLOW_DELAY_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			respond(exchange, 200, SLOW_BODY);
		});
		SERVER.start();
	}

	private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
		throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "text/plain;charset=UTF-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

	@DynamicPropertySource
	static void serverUrl(DynamicPropertyRegistry registry) {
		registry.add("cb-it.base-url", () -> "http://localhost:" + SERVER.getAddress().getPort());
	}

	@AfterAll
	static void stopServer() {
		SERVER.stop(0);
	}

	@FeignClient(name = "cb-server-error", url = "${cb-it.base-url}")
	interface ServerErrorClient {
		@GetMapping("/fail")
		String call();
	}

	@FeignClient(name = "cb-client-error", url = "${cb-it.base-url}")
	interface ClientErrorClient {
		@GetMapping("/notfound")
		String call();
	}

	@FeignClient(name = "cb-slow", url = "${cb-it.base-url}")
	interface SlowClient {
		@GetMapping("/slow")
		String call();
	}

	@FeignClient(name = "cb-timeout", url = "${cb-it.base-url}")
	interface TimeoutClient {
		@GetMapping("/slow")
		String call();
	}

	@SpringBootConfiguration
	@ImportAutoConfiguration({
		org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class,
		org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration.class,
		org.springframework.cloud.openfeign.FeignAutoConfiguration.class,
		io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration.class,
		io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration.class,
		org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JAutoConfiguration.class
	})
	@EnableFeignClients(clients = {
		ServerErrorClient.class, ClientErrorClient.class, SlowClient.class, TimeoutClient.class
	})
	@Import({InternalFeignConfig.class, FeignCircuitBreakerExceptionUnwrapper.class})
	static class App {
	}

	@Autowired
	Resilience4JCircuitBreakerFactory circuitBreakerFactory;

	@Autowired
	ServerErrorClient serverErrorClient;

	@Autowired
	ClientErrorClient clientErrorClient;

	@Autowired
	SlowClient slowClient;

	@Autowired
	TimeoutClient timeoutClient;

	private CircuitBreaker breakerOf(String name) {
		return circuitBreakerFactory.getCircuitBreakerRegistry()
			.find(name)
			.orElseThrow(() -> new AssertionError("브레이커가 없다: " + name));
	}

	@Test
	@DisplayName("5xx 가 최소 호출 수만큼 쌓이면 브레이커가 열려 서버에 닿지 않는다")
	void opensOnServerErrors() {
		int minimumCalls = 5; // feign-resilience.yml 의 minimum-number-of-calls

		for (int i = 0; i < minimumCalls; i++) {
			assertThatThrownBy(serverErrorClient::call).isInstanceOf(FeignException.class);
		}

		int hitsBeforeOpen = FAIL_HITS.get();
		assertThatThrownBy(serverErrorClient::call).isInstanceOf(CallNotPermittedException.class);
		assertThat(FAIL_HITS.get())
			.as("차단된 호출은 서버에 도달하면 안 된다")
			.isEqualTo(hitsBeforeOpen);

		// 브레이커 이름이 메서드가 아니라 Feign 클라이언트 name 그대로인지,
		// yml 공통값이 그 브레이커에 들어갔는지도 여기서 같이 확인한다.
		CircuitBreaker breaker = breakerOf("cb-server-error");
		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(breaker.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(minimumCalls);
		assertThat(breaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(10);
	}

	@Test
	@DisplayName("4xx 는 실패로 세지 않는다 — 비즈니스 에러로 브레이커가 열리면 안 된다")
	void ignoresClientErrors() {
		for (int i = 0; i < 8; i++) {
			assertThatThrownBy(clientErrorClient::call)
				.isInstanceOf(FeignException.NotFound.class);
		}

		CircuitBreaker breaker = breakerOf("cb-client-error");
		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
	}

	@Test
	@DisplayName("read-timeout 안쪽의 느린 호출은 성공한다 — 기본 TimeLimiter(1s) 미개입 증명")
	void slowCallWithinReadTimeoutSucceeds() {
		// disable-thread-pool 이 풀리면 Spring Cloud 기본 TimeLimiter(1s)가
		// 이 1.5s 호출을 read-timeout(3s) 전에 끊어 이 테스트가 깨진다.
		assertThat(slowClient.call()).isEqualTo(SLOW_BODY);
	}

	@Test
	@DisplayName("read-timeout 초과는 RetryableException 으로 터지고 실패로 집계된다")
	void timeoutCountsAsFailure() {
		assertThatThrownBy(timeoutClient::call).isInstanceOf(RetryableException.class);

		assertThat(breakerOf("cb-timeout").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
	}
}
