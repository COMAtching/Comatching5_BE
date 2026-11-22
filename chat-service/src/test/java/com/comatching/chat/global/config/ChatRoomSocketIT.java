package com.comatching.chat.global.config;

import java.lang.reflect.Type;
import java.time.Duration;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
@SpringBootTest(classes = ChatRoomSocketIT.Config.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=chat-socket-test")
class ChatRoomSocketIT {
    @Configuration
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, WebSocketServletAutoConfiguration.class})
    @Import({WebSocketConfig.class, WebSocketHandshakeInterceptor.class, ChatRoomPresenceTracker.class})
    static class Config {}

    @LocalServerPort int port;
    @Autowired SimpMessagingTemplate sockets;
    @Autowired ChatRoomPresenceTracker presence;
    @MockitoBean ChatRoomService roomService;

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

    private StompSession connect(WebSocketStompClient client, String memberId) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.set("X-Member-Id", memberId);
        headers.set("X-Member-Role", "USER");
        return client.connectAsync("ws://127.0.0.1:" + port + "/ws/chat", headers, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    private StompFrameHandler inbox(BlockingQueue<String> queue) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return String.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) { queue.add((String) payload); }
        };
    }
}
