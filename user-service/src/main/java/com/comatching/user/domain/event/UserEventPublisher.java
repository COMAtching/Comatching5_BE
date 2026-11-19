package com.comatching.user.domain.event;

import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.common.dto.event.member.MemberAuthEvent;
import com.comatching.common.dto.event.member.MemberWithdrawnEvent;
import com.comatching.common.dto.member.ProfileResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String TOPIC_SIGNUP = "member-signup";
    private static final String TOPIC_WITHDRAW = "member-withdraw";
    private static final String TOPIC_PROFILE_UPDATED = "profile-updates";

    /**
     * 회원가입 이벤트 발행
     */
    public void sendSignupEvent(ProfileResponse profile) {
        MemberAuthEvent event = MemberAuthEvent.builder()
                .memberId(profile.memberId())
                .email(profile.email())
                .nickname(profile.nickname())
                .type(MemberAuthEvent.EventType.SIGNUP)
                .build();

        sendToKafka(TOPIC_SIGNUP, memberKey(profile.memberId()), event);
    }

    public void sendProfileUpdatedMatchingEvent(ProfileUpdatedMatchingEvent event) {
        sendToKafka(TOPIC_PROFILE_UPDATED, memberKey(event.memberId()), event);
    }

    /**
     * 회원 탈퇴 이벤트 발행
     */
    public void sendWithdrawEvent(Long memberId, String email) {
        MemberWithdrawnEvent event = new MemberWithdrawnEvent(
                memberId,
                email,
                LocalDateTime.now()
        );

        sendToKafka(TOPIC_WITHDRAW, memberKey(memberId), event);
    }

    // Kafka 의 순서 보장 단위는 토픽이 아니라 파티션이다. 키가 없으면 라운드로빈으로
    // 흩어져서, 파티션을 2개 이상으로 늘리는 순간 같은 회원의 이벤트끼리 순서가 뒤집힐수 있다.
    // memberId 를 키로 걸어 회원 단위 순서를 파티션 수와 무관하게 고정한다.
    private String memberKey(Long memberId) {
        return String.valueOf(memberId);
    }

    private void sendToKafka(String topic, String key, Object event) {
        try {
            kafkaTemplate.send(topic, key, event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send event to Kafka topic: {}", topic, ex);
                        }
                    });
        } catch (RuntimeException ex) {
            log.error("Failed to send event to Kafka topic: {}", topic, ex);
        }
    }
}
