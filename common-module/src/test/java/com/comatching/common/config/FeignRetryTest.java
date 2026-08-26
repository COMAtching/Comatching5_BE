package com.comatching.common.config;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import feign.RetryableException;

/**
 * GET 한정 1회 재시도(InternalGetRetryer) 검증.
 *
 * 핵심은 "몇 번 다시 보냈는가"이므로, 요청을 받고도 절대 응답하지 않는
 * 소켓 서버를 상대로 실제 연결 횟수를 센다. read-timeout 마다 연결이
 * 죽으므로 시도 1회 = 연결 1회다.
 *
 *  - GET: 원 호출 + 재시도 1회 = 연결 2회 후 RetryableException
 *  - POST: 재시도 없이 연결 1회 후 즉시 RetryableException —
 *    전송 실패는 서버가 처리를 했는지 알 수 없는 상태라, 비멱등 요청을
 *    다시 보내면 중복 실행(아이템 이중 차감 등)이 된다
 *
 * 서킷브레이커 경로(feign-resilience.yml)를 그대로 물고 돌므로, 재시도가
 * 브레이커 안에서도 동작한다는 것까지 함께 검증된다.
 */
@SpringBootTest(
	classes = FeignRetryTest.App.class,
	webEnvironment = SpringBootTest.WebEnvironment.NONE,
	properties = {
		"spring.config.import=classpath:feign-resilience.yml",
		// 기본 read-timeout(3s) x 시도 2회를 기다리지 않도록 짧게 줄인다
		"spring.cloud.openfeign.client.config.retry-get.connect-timeout=1000",
		"spring.cloud.openfeign.client.config.retry-get.read-timeout=300",
		"spring.cloud.openfeign.client.config.retry-post.connect-timeout=1000",
		"spring.cloud.openfeign.client.config.retry-post.read-timeout=300"
	}
)
@DisplayName("Feign GET 한정 재시도")
class FeignRetryTest {

	private static final ServerSocket SILENT_SERVER;
	private static final AtomicInteger CONNECTIONS = new AtomicInteger();
	private static final List<Socket> ACCEPTED = new CopyOnWriteArrayList<>();

	static {
		try {
			SILENT_SERVER = new ServerSocket(0);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		Thread acceptLoop = new Thread(() -> {
			try {
				while (true) {
					Socket socket = SILENT_SERVER.accept();
					CONNECTIONS.incrementAndGet();
					ACCEPTED.add(socket); // 읽지도 답하지도 않는다 - 클라이언트는 read-timeout 으로 끊는다
				}
			} catch (IOException e) {
				// 서버 소켓 close 로 종료
			}
		}, "silent-server-accept");
		acceptLoop.setDaemon(true);
		acceptLoop.start();
	}

	@DynamicPropertySource
	static void serverUrl(DynamicPropertyRegistry registry) {
		registry.add("retry-it.base-url", () -> "http://localhost:" + SILENT_SERVER.getLocalPort());
	}

	@AfterAll
	static void stopServer() throws IOException {
		SILENT_SERVER.close();
		for (Socket socket : ACCEPTED) {
			socket.close();
		}
	}

	@FeignClient(name = "retry-get", url = "${retry-it.base-url}")
	interface GetClient {
		@GetMapping("/anything")
		String call();
	}

	@FeignClient(name = "retry-post", url = "${retry-it.base-url}")
	interface PostClient {
		@PostMapping("/anything")
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
	@EnableFeignClients(clients = {GetClient.class, PostClient.class})
	@Import({InternalFeignConfig.class, FeignCircuitBreakerExceptionUnwrapper.class})
	static class App {
	}

	@Autowired
	GetClient getClient;

	@Autowired
	PostClient postClient;

	@Test
	@DisplayName("GET 은 한 번 재시도한다 - 연결 2회 후 실패")
	void getRetriesOnce() {
		int before = CONNECTIONS.get();

		assertThatThrownBy(getClient::call).isInstanceOf(RetryableException.class);

		assertThat(CONNECTIONS.get() - before).isEqualTo(2);
	}

	@Test
	@DisplayName("POST 는 재시도하지 않는다 - 연결 1회 후 즉시 실패")
	void postDoesNotRetry() {
		int before = CONNECTIONS.get();

		assertThatThrownBy(postClient::call).isInstanceOf(RetryableException.class);

		assertThat(CONNECTIONS.get() - before).isEqualTo(1);
	}
}
