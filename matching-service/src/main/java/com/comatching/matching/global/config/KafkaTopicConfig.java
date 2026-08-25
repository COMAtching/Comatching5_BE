package com.comatching.matching.global.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * MatchingCandidate 를 조작하는 두 토픽의 스펙을 코드로 못박는다.
 *
 * == 왜 여기(소비자)에서 선언하는가 ==
 * 파티션 수는 "얼마나 병렬로 소비해야 하는가"에서 나오고, 그 요구를 아는 쪽은
 * matching-service 다. 브로커 auto-create 에 맡기면 파티션 1개짜리 기본값으로
 * 만들어지고, 운영 환경(auto.create.topics.enable=false)에서는 토픽이 아예 없어
 * 발행·구독이 실패한다.
 *
 * == 증설만 되고 축소는 안 된다 ==
 * KafkaAdmin 은 기동 시 기존 토픽의 파티션이 선언보다 적으면 늘려 주지만
 * 줄이지는 못한다(Kafka 자체가 축소를 지원하지 않음). 즉 이 선언은 안전하게
 * 멱등 적용된다. 단, 증설 전에 프로듀서 키 지정이 선행되어야 한다 —
 * 키 없이 늘리면 같은 회원의 이벤트가 파티션에 흩어져 순서가 깨진다.
 *
 * 복제 계수 1 은 현재 브로커가 1대라서다. 브로커를 늘리면 여기 숫자만 올리면 된다.
 */
@Configuration
@ConditionalOnProperty(name = "matching.kafka.topics.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaTopicConfig {

	public static final int CANDIDATE_TOPIC_PARTITIONS = 3;

	// @KafkaListener 의 concurrency 는 String 만 받는다. 파티션 수보다 큰 값은
	// 유휴 스레드만 만들므로 두 상수는 반드시 같은 값을 가리켜야 한다.
	public static final String CANDIDATE_LISTENER_CONCURRENCY = "" + CANDIDATE_TOPIC_PARTITIONS;

	private static final short REPLICATION_FACTOR = 1;

	@Bean
	public NewTopic memberWithdrawTopic() {
		return TopicBuilder.name("member-withdraw")
			.partitions(CANDIDATE_TOPIC_PARTITIONS)
			.replicas(REPLICATION_FACTOR)
			.build();
	}

	@Bean
	public NewTopic profileUpdatesTopic() {
		return TopicBuilder.name("profile-updates")
			.partitions(CANDIDATE_TOPIC_PARTITIONS)
			.replicas(REPLICATION_FACTOR)
			.build();
	}
}
