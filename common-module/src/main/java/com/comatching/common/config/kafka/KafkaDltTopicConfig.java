package com.comatching.common.config.kafka;

import java.util.List;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * DLT 토픽 스펙을 코드로 박는다.
 *
 * 운영 환경은 auto.create.topics.enable=false 가 일반적이라 DLT 토픽이 없으면
 * 재시도를 다 쓴 메시지를 옮길 곳이 없어 그대로 사라진다.
 * 유실을 막으려고 만든 장치가 유실 지점이 되지 않게 하려는 선언이다.
 *
 * 소비하는 토픽은 서비스마다 다르므로 목록은 각 서비스 yml 이 준다.
 * 프로퍼티가 없으면 빈을 만들지 않는다 - 브로커가 없는 환경에서 KafkaAdmin 이
 * 접속을 시도하며 기다리지 않게 하려는 것이다.
 */
@Configuration
@ConditionalOnProperty(name = "comatching.kafka.dlt-topics")
public class KafkaDltTopicConfig {

	// DLT 는 사람이 열어 보고 원인을 고친 뒤 재처리하는 저용량 토픽이다.
	// 파티션 1개면 원본이 몇 개든 전역 순서가 그대로 보인다.
	// DeadLetterPublishingRecoverer 가 파티션을 -1 로 넘기므로 원본이 3파티션이어도
	// 파티션 번호가 어긋날 일은 없다.
	private static final int DLT_PARTITIONS = 1;
	private static final short DLT_REPLICATION_FACTOR = 1;

	private final List<String> sourceTopics;

	public KafkaDltTopicConfig(@Value("${comatching.kafka.dlt-topics}") List<String> sourceTopics) {
		this.sourceTopics = sourceTopics;
	}

	@Bean
	public KafkaAdmin.NewTopics deadLetterTopics() {
		NewTopic[] topics = sourceTopics.stream()
			.map(String::trim)
			.filter(name -> !name.isEmpty())
			.map(name -> TopicBuilder.name(name + KafkaConsumerConfig.DLT_SUFFIX)
				.partitions(DLT_PARTITIONS)
				.replicas(DLT_REPLICATION_FACTOR)
				.build())
			.toArray(NewTopic[]::new);

		return new KafkaAdmin.NewTopics(topics);
	}
}
