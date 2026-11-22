package com.comatching.notification.domain.service.fcm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.comatching.common.dto.event.chat.ChatMessageEvent;
import com.comatching.notification.domain.delivery.DeliveryNotSentException;
import com.comatching.common.exception.BusinessException;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.IncomingHttpResponse;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class FCMServiceTest {

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	@Mock
	private FirebaseMessageSender firebaseMessageSender;

	@Test
	@DisplayName("FCM 토큰을 저장한다")
	void saveToken_storesTrimmedToken() {
		// given
		FCMService fcmService = service();
		given(redisTemplate.opsForValue()).willReturn(valueOperations);

		// when
		fcmService.saveToken(1L, " token ");

		// then
		then(valueOperations).should().set("fcm:token:1", "token", 60L, TimeUnit.DAYS);
	}

	@Test
	@DisplayName("빈 FCM 토큰은 저장하지 않는다")
	void saveToken_rejectsBlankToken() {
		// given
		FCMService fcmService = service();

		// when & then
		assertThatThrownBy(() -> fcmService.saveToken(1L, " "))
			.isInstanceOf(BusinessException.class);
		then(redisTemplate).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("FCM 토큰이 없으면 알림을 보내지 않고 missing 로그를 남긴다")
	void sendNotification_skipsWhenTokenMissing(CapturedOutput output) {
		// given
		FCMService fcmService = service();
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.get("fcm:token:2")).willReturn(null);

		// when
		fcmService.sendNotification(event());

		// then
		assertThat(output).contains("fcm.token.missing targetUserId=2");
		then(firebaseMessageSender).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("FCM 알림 전송 성공 로그를 남긴다")
	void sendNotification_sendsMessage(CapturedOutput output) throws Exception {
		// given
		FCMService fcmService = service();
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.get("fcm:token:2")).willReturn("token");
		given(firebaseMessageSender.send(any(Message.class))).willReturn("firebase-message-id");

		// when
		fcmService.sendNotification(event());

		// then
		assertThat(output).contains("fcm.send.success targetUserId=2 roomId=room-1 firebaseMessageId=firebase-message-id");
		then(firebaseMessageSender).should().send(any(Message.class));
	}

	@Test
	@DisplayName("UNREGISTERED는 전송에 사용한 토큰의 조건부 삭제를 요청하고 정상 종료한다")
	void sendNotification_deletesOnlyTheFailedToken(CapturedOutput output) throws Exception {
		// given
		FCMService fcmService = service();
		FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
		given(exception.getMessagingErrorCode()).willReturn(MessagingErrorCode.UNREGISTERED);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.get("fcm:token:2")).willReturn("token");
		willThrow(exception).given(firebaseMessageSender).send(any(Message.class));
		given(redisTemplate.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
			eq(List.of("fcm:token:2")), eq("token"))).willReturn(1L);

		// when
		fcmService.sendNotification(event());

		// then
		assertThat(output)
			.contains("fcm.send.skipped targetUserId=2 roomId=room-1 reason=unregistered")
			.contains("fcm.token.cleanup targetUserId=2 deleted=true");
		then(redisTemplate).should().execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
			eq(List.of("fcm:token:2")), eq("token"));
		then(redisTemplate).should(never()).delete(anyString());
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

	@Test
	@DisplayName("Redis 조회 장애를 토큰 없음으로 간주하지 않고 전파한다")
	void redisFailurePropagates() {
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		RuntimeException failure = new RedisConnectionFailureException("Redis unavailable");
		given(valueOperations.get("fcm:token:2")).willThrow(failure);
		assertThatThrownBy(() -> service().sendNotification(event())).isInstanceOf(DeliveryNotSentException.class).hasCause(failure);
		then(firebaseMessageSender).shouldHaveNoInteractions();
	}

	private FirebaseMessagingException failsWith(MessagingErrorCode code) throws Exception {
		prepareToken();
		FirebaseMessagingException failure = mock(FirebaseMessagingException.class);
		given(failure.getMessagingErrorCode()).willReturn(code);
		willThrow(failure).given(firebaseMessageSender).send(any(Message.class));
		return failure;
	}

	private void prepareToken() {
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.get("fcm:token:2")).willReturn("token");
	}

	private void assertTokenUntouched() {
		then(redisTemplate).should(never()).delete(anyString());
		then(redisTemplate).should(never()).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
			anyList(), any(Object[].class));
	}

	private FCMService service() {
		return new FCMService(redisTemplate, firebaseMessageSender);
	}

	private ChatMessageEvent event() {
		return new ChatMessageEvent(
			2L,
			"sender",
			"room-1",
			"hello",
			"2026-05-18T00:00:00",
			"CHAT", "message-1"
		);
	}
}
