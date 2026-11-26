package com.comatching.common.socket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;

import com.comatching.common.auth.LoginSession;
import com.comatching.common.auth.TrustedSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

/** One registry per simple broker. Every outbound write is tied to the physical recipient socket. */
public final class SocketSessionGuard implements WebSocketMessageBrokerConfigurer, MessageListener, AutoCloseable {
    public static final String ATTRIBUTE = TrustedSession.class.getName();
    private static final CloseStatus ENDED = new CloseStatus(4403, "Session ended");
    private static final CloseStatus EXPIRED = new CloseStatus(4401, "Access expired");
    private static final Logger log = LoggerFactory.getLogger(SocketSessionGuard.class);

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(2, task -> {
        Thread thread = new Thread(task, "socket-session-expiry");
        thread.setDaemon(true);
        return thread;
    });
    private final Predicate<TrustedSession> permitted;
    private final BooleanSupplier serviceOpen;

    private record Connection(WebSocketSession socket, TrustedSession identity, ScheduledFuture<?> expiry) {}

    public SocketSessionGuard(Predicate<TrustedSession> permitted) {
        this(() -> true, permitted);
    }

    public SocketSessionGuard(BooleanSupplier serviceOpen, Predicate<TrustedSession> permitted) {
        this.serviceOpen = serviceOpen;
        this.permitted = permitted;
        timer.setRemoveOnCancelPolicy(true);
        // Reconcile idle connections after a missed Pub/Sub notification or an administrator role change.
        timer.scheduleWithFixedDelay(this::reconcile, 3, 3, TimeUnit.SECONDS);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(this::decorate);
    }

    public WebSocketHandler decorate(WebSocketHandler delegate) {
        return new WebSocketHandlerDecorator(delegate) {
            @Override public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
                Object value = socket.getAttributes().get(ATTRIBUTE);
                if (!(value instanceof TrustedSession identity) || !isServiceOpen() || !allowed(identity)) {
                    reject(socket, value instanceof TrustedSession id && !id.expiresAt().isAfter(Instant.now()) ? EXPIRED : ENDED);
                    return;
                }
                long millis = Math.max(1, Duration.between(Instant.now(), identity.expiresAt()).toMillis());
                var connection = connections.computeIfAbsent(socket.getId(), ignored ->
                        new Connection(socket, identity, timer.schedule(() -> reject(socket, EXPIRED), millis, TimeUnit.MILLISECONDS)));
                if (connection.socket() != socket) {
                    reject(socket, ENDED);
                    return;
                }
                try {
                    // Spring may queue a broker message or buffer it behind an earlier write on this socket.
                    super.afterConnectionEstablished(new WebSocketSessionDecorator(socket) {
                        @Override public void sendMessage(WebSocketMessage<?> message) throws IOException {
                            if (connections.get(socket.getId()) == connection && check(connection, isServiceOpen())) {
                                super.sendMessage(message);
                            }
                        }
                    });
                } catch (Exception failure) {
                    forget(socket);
                    throw failure;
                }
            }

            @Override public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) throws Exception {
                forget(socket);
                super.afterConnectionClosed(socket, status);
            }
        };
    }

    @Override public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                var type = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
                if (type == SimpMessageType.DISCONNECT || type == SimpMessageType.UNSUBSCRIBE) return message;
                if (type == SimpMessageType.CONNECT) {
                    var frame = StompHeaderAccessor.wrap(message);
                    String expected = frame.getFirstNativeHeader(LoginSession.EXPECTED_HEADER);
                    if (expected != null && frame.getSessionId() != null) {
                        Connection connection = connections.get(frame.getSessionId());
                        if (connection == null) return null;
                        if (!connection.identity().login().clientMarker().equals(expected)) {
                            reject(connection.socket(), ENDED);
                            return null;
                        }
                    }
                }
                return permittedFrame(message) ? message : null;
            }
        });
    }

    private boolean permittedFrame(Message<?> message) {
        String id = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        if (id == null) return false;
        return isUsable(id);
    }

    public boolean isUsable(String id) {
        Connection connection = connections.get(id);
        return connection != null && check(connection, isServiceOpen());
    }

    private boolean check(Connection connection, boolean open) {
        if (open && allowed(connection.identity())) return true;
        reject(connection.socket(), connection.identity().expiresAt().isAfter(Instant.now()) ? ENDED : EXPIRED);
        return false;
    }

    private boolean allowed(TrustedSession identity) {
        if (!identity.expiresAt().isAfter(Instant.now())) return false;
        try {
            return permitted.test(identity);
        } catch (RuntimeException unavailable) {
            log.debug("WebSocket session validation unavailable", unavailable);
            return false;
        }
    }

    private boolean isServiceOpen() {
        try { return serviceOpen.getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }

    private void reconcile() {
        if (connections.isEmpty()) return;
        boolean open = isServiceOpen();
        connections.values().forEach(connection -> check(connection, open));
    }

    @Override public void onMessage(org.springframework.data.redis.connection.Message notification, byte[] pattern) {
        String[] event = new String(notification.getBody(), StandardCharsets.US_ASCII).split(":", 2);
        if (event.length != 2) return;
        connections.values().forEach(connection -> {
            var owner = connection.identity().login();
            if ((event[0].equals("*") || Long.toString(owner.memberId()).equals(event[0]))
                    && (event[1].equals("*") || owner.id().toString().equals(event[1]))) check(connection, isServiceOpen());
        });
    }

    private void forget(WebSocketSession socket) {
        connections.computeIfPresent(socket.getId(), (id, connection) -> {
            if (connection.socket() != socket) return connection;
            connection.expiry().cancel(false);
            return null;
        });
    }

    private void reject(WebSocketSession socket, CloseStatus status) {
        forget(socket);
        try {
            if (socket.isOpen()) socket.close(status);
        } catch (IOException | RuntimeException failure) {
            log.debug("WebSocket transport was already closing", failure);
        }
    }

    @Override public void close() {
        timer.shutdownNow();
        connections.values().forEach(connection -> reject(connection.socket(), ENDED));
    }
}
