package com.comatching.member.infra.kafka;

import java.time.LocalDateTime;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.common.dto.event.member.MemberAuthEvent;
import com.comatching.common.dto.event.member.MemberUpdateEvent;
import com.comatching.common.dto.event.member.MemberWithdrawnEvent;
import com.comatching.common.dto.member.ProfileResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemberEventProducer {

	private final KafkaTemplate<String, String> stringKafkaTemplate;
	private final KafkaTemplate<String, Object> jsonKafkaTemplate;
	private final ObjectMapper objectMapper;

	private static final String TOPIC_SIGNUP = "member-signup";
	private static final String TOPIC_UPDATE = "member-update";
	private static final String TOPIC_WITHDRAW = "member-withdraw";
	private static final String TOPIC_MATCHING_PROFILE_UPDATE = "profile-updates"; // 매칭 서비스용 토픽

	/**
	 * 회원가입 이벤트 발행
	 * - 구독: Notification Service (환영 이메일 전송)
	 */
	public void sendSignupEvent(ProfileResponse profile) {
		MemberAuthEvent event = MemberAuthEvent.builder()
			.memberId(profile.memberId())
			.email(profile.email())
			.nickname(profile.nickname())
			.type(MemberAuthEvent.EventType.SIGNUP)
			.build();

		sendToKafka(TOPIC_SIGNUP, event);
	}

	/**
	 * 회원정보 수정 이벤트 발행
	 * - 구독: Chat Service (채팅방 내 닉네임/프사 캐시 갱신), Matching Service
	 */
	public void sendUpdateEvent(MemberUpdateEvent event) {
		sendToKafka(TOPIC_UPDATE, event);
	}

	public void sendProfileUpdatedMatchingEvent(ProfileUpdatedMatchingEvent event) {
		jsonKafkaTemplate.send(TOPIC_MATCHING_PROFILE_UPDATE, event);
	}

	/**
	 * 회원 탈퇴 이벤트 발행
	 * - 구독: Chat (참여중인 방 퇴장), Payment (결제내역 보존처리)
	 */
	public void sendWithdrawEvent(Long memberId, String email) {
		MemberWithdrawnEvent event = new MemberWithdrawnEvent(
			memberId,
			email,
			LocalDateTime.now()
		);

		sendToKafka(TOPIC_WITHDRAW, event);
	}

	private void sendToKafka(String topic, Object event) {
		try {
			String message = objectMapper.writeValueAsString(event);
			stringKafkaTemplate.send(topic, message);
		} catch (JsonProcessingException e) {
			log.error("Failed to serialize event for topic: {}", topic, e);
		} catch (Exception e) {
			log.error("Failed to send event to Kafka topic: {}", topic, e);
		}
	}
}
