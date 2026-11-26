package com.comatching.chat.global.config;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import com.comatching.common.auth.*;
import com.comatching.common.operations.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import com.comatching.chat.domain.service.chatroom.ChatRoomService;
import com.comatching.chat.domain.service.presence.ChatRoomPresenceTracker;
import com.comatching.chat.global.interceptor.WebSocketHandshakeInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Tag("integration")
@Testcontainers
@SpringBootTest(classes = ChatRoomSocketIT.Config.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=chat-socket-test","internal.service-token=socket-test-proof"})
class ChatRoomSocketIT {
    @Configuration
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, WebSocketServletAutoConfiguration.class})
    @Import({WebSocketConfig.class, WebSocketHandshakeInterceptor.class, ChatRoomPresenceTracker.class})
    static class Config {
        static final AtomicBoolean open = new AtomicBoolean(true);
        @Bean ChatRoomService roomService() { return org.mockito.Mockito.mock(ChatRoomService.class); }
        @Bean FeatureGate features() { return feature -> new FeatureState(feature, open.get(), "", 1); }
        @Bean LettuceConnectionFactory connections() { return new LettuceConnectionFactory(server.getHost(), server.getMappedPort(6379)); }
        @Bean StringRedisTemplate redis(LettuceConnectionFactory factory) { return new StringRedisTemplate(factory); }
        @Bean RedisMessageListenerContainer listener(LettuceConnectionFactory factory) {
            var listener = new RedisMessageListenerContainer();
            listener.setConnectionFactory(factory);
            return listener;
        }
    }
    @Container static final GenericContainer<?> server = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Autowired StringRedisTemplate redis;
    @org.junit.jupiter.api.BeforeEach void openChat() { Config.open.set(true); }

    @LocalServerPort int port;
    @Autowired SimpMessagingTemplate sockets;
    @Autowired ChatRoomPresenceTracker presence;

    @Test
    void roomEchoAndVisibilityRefreshReachOnlyTheirIntendedMember() throws Exception {
        var client = new WebSocketStompClient(new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        client.setMessageConverter(new StringMessageConverter());
        client.start();
        try {
            var blocker = connect(client, "20");
            var sender = connect(client, "10");
            var blockerInbox = new LinkedBlockingQueue<String>();
            var senderInbox = new LinkedBlockingQueue<String>();
            blocker.subscribe("/user/queue/chat.visibility", inbox(blockerInbox));
            sender.subscribe("/user/queue/chat.visibility", inbox(senderInbox));
            blocker.subscribe("/user/queue/chat.room.room1", inbox(blockerInbox));
            sender.subscribe("/topic/chat.room.room1", inbox(senderInbox));
            await().atMost(Duration.ofSeconds(5)).until(() -> presence.isViewing("room1", 20L) && presence.isViewing("room1", 10L));

            sockets.convertAndSendToUser("10", "/queue/chat.room.room1", "sender echo");
            sockets.convertAndSendToUser("20", "/queue/chat.visibility", "refresh");
            assertThat(senderInbox.poll(5, TimeUnit.SECONDS)).isEqualTo("sender echo");
            assertThat(blockerInbox.poll(5, TimeUnit.SECONDS)).isEqualTo("refresh");
            assertThat(blockerInbox.poll(200, TimeUnit.MILLISECONDS)).isNull();
            assertThat(senderInbox.poll(200, TimeUnit.MILLISECONDS)).isNull();
            blocker.disconnect();
            sender.disconnect();
        } finally {
            client.stop();
        }
    }

    @Test
    void revocationClosesBothNodesWithoutEndingOtherLoginAndReadOnlyStopsExistingSubscription() throws Exception {
        try (var other = new SpringApplicationBuilder(Config.class).run("--spring.config.name=chat-socket-test",
                "--server.port=0", "--server.address=127.0.0.1", "--internal.service-token=socket-test-proof")) {
            int otherPort = ((ServletWebServerApplicationContext) other).getWebServer().getPort();
            var otherBroker = other.getBean(SimpMessagingTemplate.class);
            var client = new WebSocketStompClient(new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
            client.setMessageConverter(new StringMessageConverter());
            client.start();
            try {
                var old = authority("52", Instant.now().plusSeconds(90));
                var kept = authority("52", Instant.now().plusSeconds(90));
                var installs = new com.comatching.common.auth.PushInstallations(redis);
                var browser = UUID.randomUUID();
                installs.bind(browser, old.login(), true);
                installs.bind(UUID.randomUUID(), kept.login(), true);
                var first = connect(client, port, old);
                var second = connect(client, otherPort, old);
                var remaining = connect(client, otherPort, kept);
                var keptInbox = new LinkedBlockingQueue<String>();
                remaining.subscribe("/user/queue/chat.room.room2", inbox(keptInbox));
                await().atMost(Duration.ofSeconds(5)).until(() -> other.getBean(ChatRoomPresenceTracker.class).isViewing("room2", 52L));
                var replacement = authority("54", Instant.now().plusSeconds(90));
                installs.bind(browser, replacement.login(), true);
                await().atMost(Duration.ofSeconds(6)).until(() -> !first.isConnected() && !second.isConnected());
                otherBroker.convertAndSendToUser("52", "/queue/chat.room.room2", "current login");
                assertThat(keptInbox.poll(5, TimeUnit.SECONDS)).isEqualTo("current login");
                Config.open.set(false);
                otherBroker.convertAndSendToUser("52", "/queue/chat.room.room2", "closed");
                await().atMost(Duration.ofSeconds(6)).until(() -> !remaining.isConnected());
                assertThat(keptInbox).isEmpty();
                Config.open.set(true);
                var expiring = connect(client, port, authority("53", Instant.now().plusMillis(1200)));
                await().atMost(Duration.ofSeconds(4)).until(() -> !expiring.isConnected());
            } finally { client.stop(); }
        }
    }

    private TrustedSession authority(String memberId, Instant expiresAt) {
        var session = new LoginSession(Long.parseLong(memberId), 1, UUID.randomUUID());
        redis.execute(new DefaultRedisScript<>(SessionRedisScripts.SESSION_STAMP + """
                redis.call('SET', KEYS[1], ARGV[1], 'EX', 90)
                redis.call('SET', KEYS[2], sessionStamp, 'EX', 90)
                return 1
                """, Long.class), List.of(LoginSession.versionKey(session.memberId()), session.sessionKey()), "1");
        return new TrustedSession(session, expiresAt);
    }

    private StompSession connect(WebSocketStompClient client, String memberId) throws Exception {
        return connect(client, port, authority(memberId, Instant.now().plusSeconds(60)));
    }

    private StompSession connect(WebSocketStompClient client, int serverPort, TrustedSession identity) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.set("X-Member-Id", Long.toString(identity.login().memberId()));
        headers.set("X-Member-Role", "ROLE_USER");
        identity.write(headers, "socket-test-proof");
        return client.connectAsync("ws://127.0.0.1:" + serverPort + "/ws/chat", headers, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    private StompFrameHandler inbox(BlockingQueue<String> queue) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return String.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) { queue.add((String) payload); }
        };
    }
}
