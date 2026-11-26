package com.comatching.common.socket;

import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.comatching.common.auth.LoginSession;
import com.comatching.common.auth.TrustedSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

class SocketSessionGuardTest {
    @Test
    void aQueuedFrameIsCheckedAgainAtItsRecipientTransport() throws Exception {
        var permitted = new AtomicBoolean(true);
        var physical = mock(WebSocketSession.class);
        when(physical.getId()).thenReturn("transport");
        when(physical.isOpen()).thenReturn(true);
        var owner = new TrustedSession(new LoginSession(1, 0, UUID.randomUUID()), Instant.now().plusSeconds(60));
        when(physical.getAttributes()).thenReturn(Map.of(SocketSessionGuard.ATTRIBUTE, owner));
        var delegate = mock(WebSocketHandler.class);
        try (var guard = new SocketSessionGuard(identity -> permitted.get())) {
            guard.decorate(delegate).afterConnectionEstablished(physical);
            var transport = ArgumentCaptor.forClass(WebSocketSession.class);
            verify(delegate).afterConnectionEstablished(transport.capture());
            var alreadySent = new TextMessage("before logout");
            transport.getValue().sendMessage(alreadySent);
            verify(physical).sendMessage(alreadySent);

            var queued = new TextMessage("queued before logout");
            permitted.set(false);
            transport.getValue().sendMessage(queued);
            verify(physical, never()).sendMessage(queued);
            verify(physical).close(new CloseStatus(4403, "Session ended"));
        }
    }
}
