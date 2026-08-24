package com.comatching.user.domain.event;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 메시지 키 = memberId 계약 검증.
 *
 * 파티션이 3개일 때도 같은 회원의 이벤트는 같은 파티션에 발행 순서대로 쌓여야
 * 한다는 것이 파티션 증설의 전제다. 이 전제는 프로듀서가 키를 지정할 때만
 * 성립하므로(키 없음 = 라운드로빈), 실제 브로커(EmbeddedKafka)에 3파티션
 * 토픽을 만들고 발행 결과의 키·파티션·오프셋 순서를 직접 확인한다.
 */
@EmbeddedKafka(partitions = 3, topics = {"member-withdraw", "profile-updates"})
@DisplayName("UserEventPublisher 메시지 키 검증")
class UserEventPublisherIT {

	private static final Long MEMBER_A = 101L;
	private static final Long MEMBER_B = 202L;

	@Test
	@DisplayName("같은 회원의 이벤트는 memberId 키로 같은 파티션에 발행 순서대로 쌓인다")
	void sameMemberEventsKeepOrderInOnePartition(EmbeddedKafkaBroker broker) throws Exception {
		ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

		DefaultKafkaProducerFactory<String, String> stringFactory =
			new DefaultKafkaProducerFactory<>(producerProps(broker, StringSerializer.class));
		DefaultKafkaProducerFactory<String, Object> jsonFactory =
			new DefaultKafkaProducerFactory<>(producerProps(broker, JsonSerializer.class));

		try {
			UserEventPublisher publisher = new UserEventPublisher(
				new KafkaTemplate<>(stringFactory),
				new KafkaTemplate<>(jsonFactory),
				objectMapper
			);

			// 회원 A: 갱신 3건 후 탈퇴 1건, 회원 B: 갱신 2건
			publisher.sendProfileUpdatedMatchingEvent(profileEvent(MEMBER_A, 1L));
			publisher.sendProfileUpdatedMatchingEvent(profileEvent(MEMBER_A, 2L));
			publisher.sendProfileUpdatedMatchingEvent(profileEvent(MEMBER_A, 3L));
			publisher.sendWithdrawEvent(MEMBER_A, "a@test.com");
			publisher.sendProfileUpdatedMatchingEvent(profileEvent(MEMBER_B, 1L));
			publisher.sendProfileUpdatedMatchingEvent(profileEvent(MEMBER_B, 2L));

			List<ConsumerRecord<String, String>> records = consumeAll(broker, 6);

			// 1) 모든 레코드에 memberId 가 키로 실려 있다
			List<ConsumerRecord<String, String>> withdrawRecords = byTopic(records, "member-withdraw");
			List<ConsumerRecord<String, String>> profileRecords = byTopic(records, "profile-updates");

			assertThat(withdrawRecords).hasSize(1);
			assertThat(withdrawRecords.get(0).key()).isEqualTo(String.valueOf(MEMBER_A));
			assertThat(profileRecords).hasSize(5);
			assertThat(profileRecords).allSatisfy(record ->
				assertThat(record.key()).isIn(String.valueOf(MEMBER_A), String.valueOf(MEMBER_B)));

			// 2) 같은 회원의 레코드는 파티션 3개 중 정확히 한 파티션에만 있다
			assertThat(partitionsOf(profileRecords, MEMBER_A)).hasSize(1);
			assertThat(partitionsOf(profileRecords, MEMBER_B)).hasSize(1);

			// 3) 한 파티션 안에서는 오프셋 순서 = 발행 순서다
			List<Long> memberAProfileIds = profileRecords.stream()
				.filter(record -> record.key().equals(String.valueOf(MEMBER_A)))
				.sorted((r1, r2) -> Long.compare(r1.offset(), r2.offset()))
				.map(record -> readProfileId(objectMapper, record.value()))
				.collect(Collectors.toList());
			assertThat(memberAProfileIds).containsExactly(1L, 2L, 3L);
		} finally {
			stringFactory.destroy();
			jsonFactory.destroy();
		}
	}

	private Map<String, Object> producerProps(EmbeddedKafkaBroker broker, Class<?> valueSerializer) {
		Map<String, Object> props = new HashMap<>();
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueSerializer);
		return props;
	}

	private List<ConsumerRecord<String, String>> consumeAll(EmbeddedKafkaBroker broker, int expectedCount) {
		Map<String, Object> props = new HashMap<>();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
		props.put(ConsumerConfig.GROUP_ID_CONFIG, "user-event-publisher-it");
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

		try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
			props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {

			broker.consumeFromEmbeddedTopics(consumer, "member-withdraw", "profile-updates");

			List<ConsumerRecord<String, String>> records = new ArrayList<>();
			KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), expectedCount)
				.forEach(records::add);
			return records;
		}
	}

	private List<ConsumerRecord<String, String>> byTopic(List<ConsumerRecord<String, String>> records, String topic) {
		return records.stream()
			.filter(record -> record.topic().equals(topic))
			.collect(Collectors.toList());
	}

	private List<Integer> partitionsOf(List<ConsumerRecord<String, String>> records, Long memberId) {
		return records.stream()
			.filter(record -> record.key().equals(String.valueOf(memberId)))
			.map(ConsumerRecord::partition)
			.distinct()
			.collect(Collectors.toList());
	}

	private long readProfileId(ObjectMapper objectMapper, String json) {
		try {
			return objectMapper.readTree(json).get("profileId").asLong();
		} catch (Exception e) {
			throw new IllegalStateException("이벤트 역직렬화 실패: " + json, e);
		}
	}

	private ProfileUpdatedMatchingEvent profileEvent(Long memberId, Long profileId) {
		return ProfileUpdatedMatchingEvent.builder()
			.memberId(memberId)
			.profileId(profileId)
			.gender(Gender.MALE)
			.mbti("ISTJ")
			.major("컴퓨터공학과")
			.contactFrequency(ContactFrequency.FREQUENT)
			.hobbyCategories(List.of(HobbyCategory.SPORTS))
			.birthDate(LocalDate.of(2000, 1, 1))
			.isMatchable(true)
			.build();
	}
}
