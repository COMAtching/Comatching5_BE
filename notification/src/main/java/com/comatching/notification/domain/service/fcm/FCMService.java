package com.comatching.notification.domain.service.fcm;

import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.comatching.common.dto.event.chat.ChatMessageEvent;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FCMService {

	private final StringRedisTemplate redisTemplate;

	private static final long TOKEN_EXPIRATION_DAYS = 60;

	// 유저의 FCM 토큰 저장
	public void saveToken(Long memberId, String token) {
		redisTemplate.opsForValue().set(
			"fcm:token:" + memberId,
			token,
			TOKEN_EXPIRATION_DAYS,
			TimeUnit.DAYS
		);
	}

	// 유저의 FCM 토큰 조회
	public String getToken(Long memberId) {
		return redisTemplate.opsForValue().get("fcm:token:" + memberId);
	}

	// 알림 전송
	public void sendNotification(ChatMessageEvent dto) {
		String token = getToken(dto.targetUserId());

		if (token == null) {
			log.warn("🔕 알림 실패: 해당 유저({})의 FCM 토큰이 없습니다.", dto.targetUserId());
			return;
		}

		try {
			Notification notification = Notification.builder()
				.setTitle(dto.senderNickname())
				.setBody(dto.content())
				.build();

			Message message = Message.builder()
				.setToken(token)
				.setNotification(notification)
				.putData("roomId", dto.roomId())
				.putData("senderNickname", dto.senderNickname())
				.putData("content", dto.content())
				.putData("timestamp", dto.timestamp())
				.putData("type", dto.type())
				.build();

			FirebaseMessaging.getInstance().send(message);

		} catch (Exception e) {
			log.error("❌ 알림 전송 에러", e);
		}
	}
}
