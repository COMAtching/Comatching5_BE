package com.comatching.user.domain.event;

import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.common.dto.event.member.MemberAuthEvent;
import com.comatching.common.dto.event.member.MemberWithdrawnEvent;
import com.comatching.common.dto.member.ProfileResponse;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class UserEventPublisher {

    private final MemberOutboxRepository outbox;
    private final ObjectMapper objectMapper;

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

        enqueue(TOPIC_SIGNUP, profile.memberId(), event);
    }

    public void sendProfileUpdatedMatchingEvent(ProfileUpdatedMatchingEvent event) {
        enqueue(TOPIC_PROFILE_UPDATED, event.memberId(), event);
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

        enqueue(TOPIC_WITHDRAW, memberId, event);
    }

    private void enqueue(String topic, Long memberId, Object event) {
        try {
            outbox.insert(topic, memberId, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("회원 이벤트 직렬화 실패: " + topic, e);
        }
    }
}
