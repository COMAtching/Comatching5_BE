package com.comatching.notification.domain.service.fcm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import com.comatching.common.auth.LoginSession;
import com.comatching.common.auth.PushInstallations;
import com.comatching.common.auth.TrustedSession;
import com.comatching.common.dto.event.chat.ChatMessageEvent;
import com.comatching.common.exception.BusinessException;
import com.comatching.notification.domain.delivery.DeliveryNotSentException;
import com.comatching.notification.domain.delivery.NotificationDeliveryService;
import com.google.firebase.IncomingHttpResponse;
import com.google.firebase.messaging.*;

@ExtendWith(MockitoExtension.class)
class FCMServiceTest {
    @Mock private PushInstallations installations;
    @Mock private FirebaseMessageSender firebaseMessageSender;
    @Mock private NotificationDeliveryService deliveries;
    private final UUID chatRef = UUID.randomUUID();
    private final UUID device = UUID.randomUUID();
    private final LoginSession session = new LoginSession(2, 0, UUID.randomUUID());
    private final PushInstallations.Target target = new PushInstallations.Target(device, session, "firebase-token");

    @Test void registrationRequiresTheAuthenticatedInstallation() {
        var owner = new TrustedSession(session, Instant.now().plusSeconds(60));
        given(installations.register(device, session, "token")).willReturn(true);
        service().register(device, owner, " token ");
        assertThatThrownBy(() -> service().register(UUID.randomUUID(), owner, "token")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service().register(device, owner, " ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service().register(device, null, "token")).isInstanceOf(BusinessException.class);
    }

    @Test void missingInstallationSkipsExternalDelivery() {
        given(installations.installations(2)).willReturn(Set.of());
        service().sendNotification(event());
        verifyNoInteractions(deliveries, firebaseMessageSender);
    }

    @Test void payloadContainsAnOpaqueDestinationAndGenericText() throws Exception {
        prepareToken();
        service().sendNotification(event());
        var message = ArgumentCaptor.forClass(Message.class);
        verify(firebaseMessageSender).send(message.capture());
        assertThat(ReflectionTestUtils.getField(message.getValue(), "notification")).isNull();
        assertThat(ReflectionTestUtils.getField(message.getValue(), "data")).isEqualTo(
                Map.of("type", "CHAT", "title", "코매칭", "body", "새 메시지가 있습니다.", "chatRef", chatRef.toString()));
    }

    @Test void invalidFirebaseTokenIsConditionallyCleared() throws Exception {
        failsWith(MessagingErrorCode.UNREGISTERED);
        service().sendNotification(event());
        verify(installations).invalidate(target);
    }

	@ParameterizedTest
	@EnumSource(value = MessagingErrorCode.class,
		names = {"INVALID_ARGUMENT", "SENDER_ID_MISMATCH", "THIRD_PARTY_AUTH_ERROR", "QUOTA_EXCEEDED"})
	@DisplayName("요청·인증·쿼터 오류는 토큰을 보존하고 DLT용 예외를 전달한다")
	void permanentOrQuotaErrorGoesToDlt(MessagingErrorCode code) throws Exception {
		FirebaseMessagingException failure = failsWith(code);
		assertThatThrownBy(() -> service().sendNotification(event()))
			.isExactlyInstanceOf(FcmDltException.class).hasCause(failure);
		assertTokenUntouched();
	}

	@ParameterizedTest
	@NullSource
	@EnumSource(value = MessagingErrorCode.class, names = {"INTERNAL", "UNAVAILABLE"})
	@DisplayName("수락 여부가 모호한 오류는 발송 원장이 UNKNOWN으로 분류하도록 전달한다")
	void transientFailurePropagates(MessagingErrorCode code) throws Exception {
		FirebaseMessagingException failure = failsWith(code);
		assertThatThrownBy(() -> service().sendNotification(event()))
			.isExactlyInstanceOf(IllegalStateException.class).hasCause(failure);
		assertTokenUntouched();
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 401, 403, 404, 429})
	@DisplayName("FCM 세부 코드가 없어도 HTTP 상태로 DLT 대상을 판별한다")
	void classifiesHttpStatusWithoutMessagingCode(int status) throws Exception {
		FirebaseMessagingException failure = failsWith(null);
		IncomingHttpResponse response = mock(IncomingHttpResponse.class);
		given(failure.getHttpResponse()).willReturn(response);
		given(response.getStatusCode()).willReturn(status);
		assertThatThrownBy(() -> service().sendNotification(event()))
			.isExactlyInstanceOf(FcmDltException.class).hasCause(failure);
		assertTokenUntouched();
	}

	@ParameterizedTest
	@MethodSource("retryAfterHeaders")
	@DisplayName("Retry-After의 헤더 대소문자·문자열·목록 형식을 읽고 자동 재시도를 보류한다")
	void retryAfterParksInsteadOfRetrying(Map<String, Object> headers) throws Exception {
		FirebaseMessagingException failure = failsWith(MessagingErrorCode.UNAVAILABLE);
		IncomingHttpResponse response = mock(IncomingHttpResponse.class);
		given(failure.getHttpResponse()).willReturn(response);
		given(response.getStatusCode()).willReturn(503);
		given(response.getHeaders()).willReturn(headers);
		assertThatThrownBy(() -> service().sendNotification(event()))
			.isExactlyInstanceOf(FcmDltException.class).hasCause(failure)
			.hasMessageContaining("retryAfter=120").hasMessageContaining("failedAt=");
		assertTokenUntouched();
	}

	static Stream<Map<String, Object>> retryAfterHeaders() {
		return Stream.of(Map.of("Retry-After", "120"), Map.of("retry-after", List.of("120")));
	}

	@Test
	@DisplayName("Firebase 미초기화 오류는 DLT용 예외로 전달한다")
	void initializationFailureGoesToDlt() throws Exception {
		prepareToken();
		FcmDltException failure = new FcmDltException("FCM 초기화 오류", new IllegalStateException());
		willThrow(failure).given(firebaseMessageSender).send(any(Message.class));
		assertThatThrownBy(() -> service().sendNotification(event()))
			.isSameAs(failure);
		assertTokenUntouched();
	}


    @Test void redisFailureIsRetried() {
        var failure = new RedisConnectionFailureException("Redis unavailable");
        given(installations.installations(2L)).willThrow(failure);
        assertThatThrownBy(() -> service().sendNotification(event())).isInstanceOf(DeliveryNotSentException.class).hasCause(failure);
        then(firebaseMessageSender).shouldHaveNoInteractions();
    }

    private FirebaseMessagingException failsWith(MessagingErrorCode code) throws Exception {
        prepareToken();
        var failure = mock(FirebaseMessagingException.class);
        given(failure.getMessagingErrorCode()).willReturn(code);
        willThrow(failure).given(firebaseMessageSender).send(any(Message.class));
        return failure;
    }

    private void prepareToken() {
        given(installations.installations(2L)).willReturn(Set.of(device.toString()));
        given(installations.target(device, 2L)).willReturn(target);
        given(installations.rememberRoom(target, "room-1")).willReturn(chatRef);
        doAnswer(invocation -> invocation.getArgument(2, Supplier.class).get()).when(deliveries).deliver(anyString(), anyString(), any());
    }

    private void assertTokenUntouched() {
        then(installations).should(never()).invalidate(any());
    }

    private FCMService service() { return new FCMService(installations, firebaseMessageSender, deliveries); }

    private ChatMessageEvent event() {
        return new ChatMessageEvent(2L, "sender", "room-1", "hello", "2026-05-18T00:00:00", "CHAT", "message-1");
    }
}
