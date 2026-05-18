package com.comatching.notification.domain.service.fcm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.comatching.common.dto.event.chat.ChatMessageEvent;
import com.comatching.common.exception.BusinessException;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;

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
	@DisplayName("invalid FCM 토큰이면 토큰을 삭제한다")
	void sendNotification_deletesInvalidToken(CapturedOutput output) throws Exception {
		// given
		FCMService fcmService = service();
		FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
		given(exception.getMessagingErrorCode()).willReturn(MessagingErrorCode.UNREGISTERED);
		given(exception.getMessage()).willReturn("invalid token");
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.get("fcm:token:2")).willReturn("token");
		willThrow(exception).given(firebaseMessageSender).send(any(Message.class));

		// when
		fcmService.sendNotification(event());

		// then
		assertThat(output)
			.contains("fcm.send.failure targetUserId=2 roomId=room-1 errorCode=UNREGISTERED")
			.contains("fcm.token.deleted targetUserId=2 reason=invalid-token");
		then(redisTemplate).should().delete("fcm:token:2");
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
			"CHAT"
		);
	}
}
