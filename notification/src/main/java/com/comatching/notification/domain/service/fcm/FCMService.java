package com.comatching.notification.domain.service.fcm;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.comatching.common.auth.PushInstallations;
import com.comatching.common.auth.TrustedSession;
import com.comatching.common.dto.event.chat.ChatMessageEvent;
import com.comatching.common.dto.event.order.ItemsGranted;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.notification.domain.delivery.DeliveryNotSentException;
import com.comatching.notification.domain.delivery.DeliveryOutcome;
import com.comatching.notification.domain.delivery.NotificationDeliveryService;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class FCMService {
    private final PushInstallations installations;
    private final FirebaseMessageSender firebase;
    private final NotificationDeliveryService deliveries;

    public FCMService(PushInstallations installations, FirebaseMessageSender firebase, NotificationDeliveryService deliveries) {
        this.installations = installations;
        this.firebase = firebase;
        this.deliveries = deliveries;
    }

    public void register(UUID installation, TrustedSession owner, String token) {
        if (owner == null || !owner.expiresAt().isAfter(Instant.now())) throw new BusinessException(GeneralErrorCode.UNAUTHORIZED);
        if (installation == null || !StringUtils.hasText(token) || token.length() > 4096) {
            throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE);
        }
        try {
            if (!installations.register(installation, owner.login(), token.trim())) {
                throw new BusinessException(GeneralErrorCode.UNAUTHORIZED);
            }
        } catch (DataAccessException unavailable) {
            throw new BusinessException(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
        }
    }

    public DeliveryOutcome sendNotification(ChatMessageEvent event) {
        if (!StringUtils.hasText(event.roomId())) return DeliveryOutcome.SKIPPED;
        return dispatch(event.targetUserId(), event.eventId(), event.roomId());
    }

    public DeliveryOutcome sendItemsGranted(ItemsGranted event) {
        return dispatch(event.memberId(), event.eventId(), null);
    }

    private DeliveryOutcome dispatch(long member, String eventId, String roomId) {
        boolean chat = roomId != null;
        Set<String> devices;
        try {
            devices = installations.installations(member);
        } catch (DataAccessException unavailable) {
            throw new DeliveryNotSentException("FCM 설치 목록 조회 실패", unavailable);
        }
        if (devices.isEmpty()) {
            log.info("fcm.token.missing targetUserId={}", member);
            return DeliveryOutcome.SKIPPED;
        }
        RuntimeException pending = null;
        for (String device : devices) {
            try {
                // Fits the existing 40-character handler key. Keep the outer event ledger for old replays.
                deliveries.deliver(eventId, (chat ? "C:" : "G:") + device,
                        () -> send(member, UUID.fromString(device), roomId));
            } catch (RuntimeException failure) {
                // An uncertain or rejected installation must not prevent attempts to independent recipients.
                if (pending == null || failure instanceof DeliveryNotSentException) pending = failure;
            }
        }
        if (pending != null) throw pending;
        return DeliveryOutcome.SENT;
    }

    private DeliveryOutcome send(long member, UUID device, String roomId) {
        PushInstallations.Target target;
        try {
            target = installations.target(device, member);
        } catch (DataAccessException unavailable) {
            throw new DeliveryNotSentException("FCM 설치 소유권 조회 실패", unavailable);
        }
        if (target == null) return DeliveryOutcome.SKIPPED;
        // FCM may deliver after an account switch. The entire payload must be safe on a shared lock screen.
        boolean chat = roomId != null;
        var message = Message.builder().setToken(target.token())
                .putData("type", chat ? "CHAT" : "ITEMS_GRANTED")
                .putData("title", "코매칭")
                .putData("body", chat ? "새 메시지가 있습니다." : "아이템 지급이 완료되었습니다.");
        if (chat) {
            try {
                message.putData("chatRef", installations.rememberRoom(target, roomId).toString());
            } catch (DataAccessException unavailable) {
                throw new DeliveryNotSentException("채팅 알림 연결 저장 실패", unavailable);
            }
        }
        try {
            firebase.send(message.build());
            return DeliveryOutcome.SENT;
        } catch (FirebaseMessagingException failure) {
            if (failure.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
                try {
                    installations.invalidate(target);
                } catch (DataAccessException cleanupFailure) {
                    throw new DeliveryNotSentException("FCM 토큰 정리 실패", cleanupFailure);
                }
                return DeliveryOutcome.SKIPPED;
            }
            parkRejectedRequest(failure);
            if (failure.getMessagingErrorCode() == MessagingErrorCode.UNAVAILABLE && failure.getHttpResponse() != null
                    && failure.getHttpResponse().getStatusCode() == 503) {
                throw new DeliveryNotSentException("FCM 서비스 사용 불가", failure);
            }
            throw new IllegalStateException("FCM 수락 여부 확인 불가", failure);
        }
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

}
