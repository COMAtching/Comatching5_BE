package com.comatching.notification.domain.service.fcm;

import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.comatching.common.dto.event.chat.ChatMessageEvent;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FCMService {

	private final StringRedisTemplate redisTemplate;
	private final FirebaseMessageSender firebaseMessageSender;

	private static final long TOKEN_EXPIRATION_DAYS = 60;
	private static final String FCM_TOKEN_KEY_PREFIX = "fcm:token:";

	// 유저의 FCM 토큰 저장
	public void saveToken(Long memberId, String token) {
		if (!StringUtils.hasText(token)) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "FCM 토큰은 필수입니다.");
		}

		redisTemplate.opsForValue().set(
			getTokenKey(memberId),
			token.trim(),
			TOKEN_EXPIRATION_DAYS,
			TimeUnit.DAYS
		);
	}

	// 유저의 FCM 토큰 조회
	public String getToken(Long memberId) {
		return redisTemplate.opsForValue().get(getTokenKey(memberId));
	}

	// 알림 전송
	public void sendNotification(ChatMessageEvent dto) {
		String token = getToken(dto.targetUserId());

		if (!StringUtils.hasText(token)) {
			log.warn("fcm.token.missing targetUserId={}", dto.targetUserId());
			return;
		}

		try {
			Notification notification = Notification.builder()
				.setTitle(safeValue(dto.senderNickname()))
				.setBody(safeValue(dto.content()))
				.build();

			Message message = Message.builder()
				.setToken(token)
				.setNotification(notification)
				.putData("roomId", safeValue(dto.roomId()))
				.putData("senderNickname", safeValue(dto.senderNickname()))
				.putData("content", safeValue(dto.content()))
				.putData("timestamp", safeValue(dto.timestamp()))
				.putData("type", safeValue(dto.type()))
				.build();

			String firebaseMessageId = firebaseMessageSender.send(message);
			log.info(
				"fcm.send.success targetUserId={} roomId={} firebaseMessageId={}",
				dto.targetUserId(),
				dto.roomId(),
				firebaseMessageId
			);

		} catch (FirebaseMessagingException e) {
			MessagingErrorCode errorCode = e.getMessagingErrorCode();
			log.warn(
				"fcm.send.failure targetUserId={} roomId={} errorCode={} error={}",
				dto.targetUserId(),
				dto.roomId(),
				errorCode,
				e.getMessage()
			);
			if (isInvalidToken(errorCode)) {
				deleteToken(dto.targetUserId());
			}
		} catch (Exception e) {
			log.error(
				"fcm.send.failure targetUserId={} roomId={} errorCode={} error={}",
				dto.targetUserId(),
				dto.roomId(),
				e.getClass().getSimpleName(),
				e.getMessage()
			);
		}
	}

	private String getTokenKey(Long memberId) {
		return FCM_TOKEN_KEY_PREFIX + memberId;
	}

	private String safeValue(String value) {
		return value != null ? value : "";
	}

	private boolean isInvalidToken(MessagingErrorCode errorCode) {
		return errorCode == MessagingErrorCode.UNREGISTERED || errorCode == MessagingErrorCode.INVALID_ARGUMENT;
	}

	private void deleteToken(Long memberId) {
		redisTemplate.delete(getTokenKey(memberId));
		log.info("fcm.token.deleted targetUserId={} reason=invalid-token", memberId);
	}
}
