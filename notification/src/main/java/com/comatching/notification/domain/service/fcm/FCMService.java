package com.comatching.notification.domain.service.fcm;

import com.comatching.common.dto.event.chat.ChatMessageEvent;
import com.comatching.common.dto.event.order.ItemsGranted;
import com.comatching.notification.domain.delivery.DeliveryNotSentException;
import com.comatching.notification.domain.delivery.DeliveryOutcome;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class FCMService {

    private static final long TOKEN_EXPIRATION_DAYS = 60;
    private static final String FCM_TOKEN_KEY_PREFIX = "fcm:token:";
    // GET과 DEL을 Redis 안에서 원자적으로 실행해 새로 등록된 토큰을 보존한다.
    private static final DefaultRedisScript<Long> DELETE_IF_UNCHANGED = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final FirebaseMessageSender firebaseMessageSender;

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
    public DeliveryOutcome sendNotification(ChatMessageEvent dto) {
        String token = tokenForDelivery(dto.targetUserId());

        if (!StringUtils.hasText(token)) {
            log.warn("fcm.token.missing targetUserId={}", dto.targetUserId());
            return DeliveryOutcome.SKIPPED;
        }

        Message message;
        try {
            Notification notification = Notification.builder()
                    .setTitle(safeValue(dto.senderNickname()))
                    .setBody(safeValue(dto.content()))
                    .build();

            message = Message.builder()
                    .setToken(token)
                    .setNotification(notification)
                    .putData("roomId", safeValue(dto.roomId()))
                    .putData("senderNickname", safeValue(dto.senderNickname()))
                    .putData("content", safeValue(dto.content()))
                    .putData("timestamp", safeValue(dto.timestamp()))
                    .putData("type", safeValue(dto.type()))
                    .putData("eventId", dto.eventId())
                    .build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new FcmDltException("FCM 메시지 구성 오류", e);
        }

        try {
            String firebaseMessageId = firebaseMessageSender.send(message);
            log.info(
                    "fcm.send.success targetUserId={} roomId={} firebaseMessageId={}",
                    dto.targetUserId(),
                    dto.roomId(),
                    firebaseMessageId
            );
            return DeliveryOutcome.SENT;
        } catch (FirebaseMessagingException e) {
            var outcome = handleSendFailure(dto.targetUserId(), token, e);
            log.info("fcm.send.skipped targetUserId={} roomId={} reason={}",
                    dto.targetUserId(), dto.roomId(), e.getMessagingErrorCode().name().toLowerCase(java.util.Locale.ROOT));
            return outcome;
        }
    }

    public DeliveryOutcome sendItemsGranted(ItemsGranted event) {
        String token = tokenForDelivery(event.memberId());
        if (!StringUtils.hasText(token)) return DeliveryOutcome.SKIPPED;

        Message message = Message.builder().setToken(token)
                .setNotification(Notification.builder().setTitle("아이템 지급 완료")
                        .setBody("구매한 아이템을 지금 사용할 수 있어요.").build())
                .putData("type", "ITEMS_GRANTED")
                .putData("orderId", Long.toString(event.orderId()))
                .putData("eventId", event.eventId())
                .build();
        try {
            firebaseMessageSender.send(message);
            return DeliveryOutcome.SENT;
        } catch (FirebaseMessagingException e) {
            return handleSendFailure(event.memberId(), token, e);
        }
    }

    private String tokenForDelivery(Long memberId) {
        try {
            return getToken(memberId);
        } catch (RuntimeException e) {
            throw new DeliveryNotSentException("FCM 토큰 조회 실패", e);
        }
    }

    private DeliveryOutcome handleSendFailure(Long memberId, String token, FirebaseMessagingException e) {
        if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
            try {
                deleteTokenIfUnchanged(memberId, token);
            } catch (RuntimeException cleanupFailure) {
                throw new DeliveryNotSentException("FCM 무효 토큰 정리 실패", cleanupFailure);
            }
            return DeliveryOutcome.SKIPPED;
        }
        parkRejectedRequest(e);
        if (e.getMessagingErrorCode() == MessagingErrorCode.UNAVAILABLE && e.getHttpResponse() != null
                && e.getHttpResponse().getStatusCode() == 503) {
            throw new DeliveryNotSentException("FCM 서비스 사용 불가", e);
        }
        throw new IllegalStateException("FCM 수락 여부 확인 불가", e);
    }

    private void parkRejectedRequest(FirebaseMessagingException e) {
        MessagingErrorCode code = e.getMessagingErrorCode();
        int status = e.getHttpResponse() == null ? 0 : e.getHttpResponse().getStatusCode();
        if (code == MessagingErrorCode.INVALID_ARGUMENT || code == MessagingErrorCode.SENDER_ID_MISMATCH
                || code == MessagingErrorCode.THIRD_PARTY_AUTH_ERROR
                || status == 400 || status == 401 || status == 403 || status == 404) {
            throw new FcmDltException("FCM 요청 또는 설정 오류. code=" + code, e);
        }
        String retryAfter = retryAfter(e);
        if (code == MessagingErrorCode.QUOTA_EXCEEDED || status == 429 || StringUtils.hasText(retryAfter)) {
            throw new FcmDltException("FCM 자동 재시도 보류. code=" + code + ", retryAfter=" + retryAfter
                    + ", failedAt=" + java.time.Instant.now(), e);
        }
    }

    private String retryAfter(FirebaseMessagingException exception) {
        if (exception.getHttpResponse() == null) {
            return null;
        }
        for (var header : exception.getHttpResponse().getHeaders().entrySet()) {
            if ("retry-after".equalsIgnoreCase(header.getKey())) {
                Object value = header.getValue();
                if (value instanceof List<?> values) {
                    value = values.isEmpty() ? null : values.get(0);
                }
                return value == null ? null : value.toString();
            }
        }
        return null;
    }

    private void deleteTokenIfUnchanged(Long memberId, String failedToken) {
        Long deleted = redisTemplate.execute(DELETE_IF_UNCHANGED, List.of(getTokenKey(memberId)), failedToken);
        log.info("fcm.token.cleanup targetUserId={} deleted={}", memberId, Long.valueOf(1).equals(deleted));
    }

    private String getTokenKey(Long memberId) {
        return FCM_TOKEN_KEY_PREFIX + memberId;
    }

    private String safeValue(String value) {
        return value != null ? value : "";
    }
}
