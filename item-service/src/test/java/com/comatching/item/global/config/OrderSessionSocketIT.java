package com.comatching.item.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.comatching.common.auth.LoginSession;
import com.comatching.common.auth.TrustedSession;
import com.comatching.item.global.interceptor.PaymentWebSocketHandshakeInterceptor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

@Tag("integration")
@SpringBootTest(classes = OrderSessionSocketIT.Config.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=order-socket-test","internal.service-token=socket-test-proof"})
class OrderSessionSocketIT {
    @Configuration
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, WebSocketServletAutoConfiguration.class})
    @Import({OrderWebSocketConfig.class, PaymentWebSocketHandshakeInterceptor.class})
    static class Config {}

    private static final AtomicBoolean admin = new AtomicBoolean(true);
    private static HttpServer users;
    @DynamicPropertySource static void userService(DynamicPropertyRegistry properties) throws IOException {
        users = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        users.createContext("/api/internal/users/17/active-admin", exchange -> {
            try (exchange) {
                if (!"authVersion=2".equals(exchange.getRequestURI().getQuery())
                        || !"socket-test-proof".equals(exchange.getRequestHeaders().getFirst("X-Internal-Token"))) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                byte[] response = Boolean.toString(admin.get()).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        users.start();
        properties.add("user-service.url", () -> "http://127.0.0.1:" + users.getAddress().getPort());
    }
    @AfterAll static void stopUsers() { if (users != null) users.stop(0); }

    @MockitoBean StringRedisTemplate redis;
    @MockitoBean(name="orderSessionRevocations") RedisMessageListenerContainer listener;
    @Autowired SimpMessagingTemplate broker;
    @LocalServerPort int port;

    @Test void removingAdminRoleStopsAnAlreadySubscribedOrderMonitor() throws Exception {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class))).thenReturn(1L);
        var client = new WebSocketStompClient(new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        client.setMessageConverter(new StringMessageConverter());
        client.start();
        try {
            var headers = new WebSocketHttpHeaders();
            headers.set("X-Member-Id", "17");
            headers.set("X-Member-Role", "ROLE_ADMIN");
            var login = new LoginSession(17, 2, UUID.randomUUID());
            new TrustedSession(login, Instant.now().plusSeconds(90)).write(headers, "socket-test-proof");
            var connect = new StompHeaders();
            connect.set(LoginSession.EXPECTED_HEADER, new LoginSession(17, 2, UUID.randomUUID()).clientMarker());
            var wrong = client.connectAsync("ws://127.0.0.1:" + port + "/ws/payment", headers, connect, new StompSessionHandlerAdapter() {});
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> wrong.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class);
            connect.set(LoginSession.EXPECTED_HEADER, login.clientMarker());
            var socket = client.connectAsync("ws://127.0.0.1:" + port + "/ws/payment", headers, connect, new StompSessionHandlerAdapter() {})
                    .get(5, TimeUnit.SECONDS);
            var inbox = new LinkedBlockingQueue<String>();
            socket.subscribe("/topic/admin/orders", new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders unused) { return String.class; }
                @Override public void handleFrame(StompHeaders unused, Object value) { inbox.add((String)value); }
            });
            await().atMost(Duration.ofSeconds(5)).until(() -> {
                broker.convertAndSend("/topic/admin/orders", "before");
                return inbox.poll(100, TimeUnit.MILLISECONDS) != null;
            });
            admin.set(false);
            broker.convertAndSend("/topic/admin/orders", "after");
            await().atMost(Duration.ofSeconds(5)).until(() -> !socket.isConnected());
            assertThat(inbox).doesNotContain("after");
        } finally { admin.set(true); client.stop(); }
    }
}
