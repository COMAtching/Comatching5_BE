package com.comatching.matching.global.config;

import java.time.Duration;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

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

	// 리스너 병렬도는 파티션 수와 달리 운영 중에 조정할 여지를 남긴다. 파티션은
	// 늘릴 수만 있는 비가역 결정이지만 concurrency 는 재기동만으로 바꿀 수 있고,
	// 적정값은 실제 트래픽과 인스턴스 수를 본 뒤에야 정할 수 있기 때문이다.
	// 조정 범위: 파티션 수(3)를 넘는 값은 유휴 스레드만 만들므로 상한은 3,
	// 줄이는 것은 자유다 — 회원 단위 순서는 "같은 키 = 같은 파티션"(프로듀서
	// 속성)으로 보장되므로 스레드 수를 줄여도 깨지지 않는다.
	public static final String CANDIDATE_LISTENER_CONCURRENCY =
		"${matching.kafka.candidate-listener-concurrency:3}";

	private static final short REPLICATION_FACTOR = 1;

	/**
	 * retention 1일. 축제 단발성 서비스라 브로커 기본 7일은 과하다.
	 *
	 * 단, "몇 분"까지는 못 줄인다. retention 은 저장 비용이 아니라 **컨슈머가
	 * 죽어 있어도 되는 최대 시간**이다. 발행됐지만 아직 소비되지 않은 메시지는
	 * retention 이 지나면 소비 여부와 무관하게 소거되므로, retention 이 컨슈머
	 * 다운타임(배포, 야간 크래시)보다 짧으면 그 사이 발행분이 조용히 사라진다.
	 * earliest 로 막은 신규 파티션 배정 지연(최대 5분)과 차단 재시도의
	 * head-of-line 지연도 이 창 안에 들어와야 한다. 밤새 장애를 아침에 발견하는
	 * 최악 시나리오를 덮는 하한이 1일이다.
	 *
	 * 탈퇴 tombstone TTL(matching.tombstone.retention-days)은 이 값의 2배와
	 * 맞물려 있다 — retention 을 바꾸면 그쪽도 같이 바꿔야 한다.
	 */
	private static final long RETENTION_MS = Duration.ofDays(1).toMillis();

	// KafkaAdmin 기본값은 기존 토픽의 config 를 건드리지 않는다(파티션 증설만).
	// retention 선언이 이미 만들어진 토픽에도 적용되게 config 정렬을 켠다 —
	// NewTopic 에 선언한 config 만 비교·수정하므로 다른 설정에는 영향이 없다.
	@Bean
	public KafkaAdmin kafkaAdmin(KafkaProperties properties) {
		KafkaAdmin admin = new KafkaAdmin(properties.buildAdminProperties(null));
		admin.setModifyTopicConfigs(true);
		return admin;
	}

	@Bean
	public NewTopic memberWithdrawTopic() {
		return TopicBuilder.name("member-withdraw")
			.partitions(CANDIDATE_TOPIC_PARTITIONS)
			.replicas(REPLICATION_FACTOR)
			.config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(RETENTION_MS))
			.build();
	}

	@Bean
	public NewTopic profileUpdatesTopic() {
		return TopicBuilder.name("profile-updates")
			.partitions(CANDIDATE_TOPIC_PARTITIONS)
			.replicas(REPLICATION_FACTOR)
			.config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(RETENTION_MS))
			.build();
	}
}
