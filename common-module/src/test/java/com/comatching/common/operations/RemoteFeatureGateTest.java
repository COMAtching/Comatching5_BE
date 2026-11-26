package com.comatching.common.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.sun.net.httpserver.HttpServer;

class RemoteFeatureGateTest {
	@Test
	void closureAndOwnerOutageRejectNewActionsWithDifferentResponses() throws Exception {
		var owner = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		var available = new AtomicBoolean(true);
		owner.createContext("/api/public/operations/CHAT", exchange -> {
			try (exchange) {
				if (!available.get()) {
					exchange.sendResponseHeaders(503, -1);
					return;
				}
				byte[] body = """
					{"feature":"CHAT","open":false,"message":"읽기 전용","version":2}
					""".getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			}
		});
		owner.start();
		try {
			var gate = new RemoteFeatureGate("http://127.0.0.1:" + owner.getAddress().getPort());
			assertThatThrownBy(() -> gate.requireOpen(Feature.CHAT)).isInstanceOfSatisfying(BusinessException.class, e -> {
				assertThat(e.getErrorCode()).isEqualTo(GeneralErrorCode.FEATURE_CLOSED);
				assertThat(e.getErrorData()).isEqualTo(new FeatureState(Feature.CHAT, false, "읽기 전용", 2));
			});
			available.set(false);
			assertThatThrownBy(() -> gate.requireOpen(Feature.CHAT)).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE));
		} finally {
			owner.stop(0);
		}
	}
}
