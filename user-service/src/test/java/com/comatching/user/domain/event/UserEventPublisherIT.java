package com.comatching.user.domain.event;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.support.OutboxMySqlSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** 실제 MySQL 커밋과 Kafka ACK를 기준으로 롤백·재시도·회원별 키를 확인한다. */
@SpringBootTest(classes = UserEventPublisherIT.Config.class)
@DirtiesContext
@ImportAutoConfiguration({KafkaAutoConfiguration.class, DataSourceAutoConfiguration.class,
	JdbcTemplateAutoConfiguration.class, JacksonAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
	TransactionAutoConfiguration.class})
@EmbeddedKafka(partitions = 3, topics = {"member-signup", "member-withdraw", "profile-updates"})
@TestPropertySource(properties = {
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"comatching.outbox.poll-interval-ms=86400000",
	"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
	"spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
	"spring.kafka.producer.value-serializer=com.comatching.common.config.kafka.EventValueSerializer",
	"spring.kafka.producer.acks=all",
	"spring.kafka.producer.properties[enable.idempotence]=true"
})
@DisplayName("회원 이벤트 outbox와 Kafka 발행")
@Tag("integration")
class UserEventPublisherIT extends OutboxMySqlSupport {

	@Configuration
	@EnableScheduling
	@ComponentScan(basePackageClasses = UserEventPublisher.class)
	@EntityScan(basePackageClasses = Member.class)
	static class Config {}

	private static final Long MEMBER_A = 101L;
	private static final Long MEMBER_B = 202L;

	@Autowired
	private UserEventPublisher publisher;

	@Autowired
	private EmbeddedKafkaBroker broker;

	@Autowired PlatformTransactionManager transactions;
	@Autowired JdbcTemplate jdbc;
	@PersistenceContext EntityManager em;
	@Autowired ScheduledAnnotationBeanPostProcessor scheduler;

	private void dispatch() {
		assertThat(scheduler.getScheduledTasks()).singleElement()
			.satisfies(task -> task.getTask().getRunnable().run());
	}

	@Test
	void rollbackRemovesBothJpaMemberAndOutbox() {
		var id = new AtomicLong();
		new TransactionTemplate(transactions).executeWithoutResult(tx -> {
			Member member = Member.builder().email("rollback@test.invalid").build();
			em.persist(member);
			id.set(member.getId());
			publisher.sendWithdrawEvent(id.get(), member.getEmail());
			assertThat(jdbc.queryForObject("select count(*) from member_outbox_events where member_id=?", Long.class, id.get())).isOne();
			tx.setRollbackOnly();
		});
		assertThat(jdbc.queryForObject("select count(*) from members where member_id=?", Long.class, id.get())).isZero();
		assertThat(jdbc.queryForObject("select count(*) from member_outbox_events where member_id=?", Long.class, id.get())).isZero();
		assertThatThrownBy(() -> publisher.sendWithdrawEvent(id.get(), "rollback@test.invalid"))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void recoversExpiredClaimButLeavesLiveClaimAlone() {
		new TransactionTemplate(transactions).executeWithoutResult(tx -> {
			publisher.sendSignupEvent(ProfileResponse.builder().memberId(501L).email("lease@test.invalid").build());
			publisher.sendSignupEvent(ProfileResponse.builder().memberId(502L).email("live@test.invalid").build());
		});
		jdbc.update("UPDATE member_outbox_events SET status='PROCESSING', processing_token='old', "
			+ "available_at=? WHERE member_id=501", LocalDateTime.now().minusHours(1));
		jdbc.update("UPDATE member_outbox_events SET status='PROCESSING', processing_token='live', "
			+ "available_at=? WHERE member_id=502", LocalDateTime.now().plusHours(1));
		dispatch();
		assertThat(jdbc.queryForList("SELECT status FROM member_outbox_events WHERE member_id IN (501,502) ORDER BY member_id",
			String.class)).containsExactly("PUBLISHED", "PROCESSING");
	}

	@Test
	void failedAckRetriesBeforeNextSignupForSameKey() throws Exception {
		long id = 303L;
		var profile = ProfileResponse.builder().memberId(id).nickname("x".repeat(4096)).email("signup@test.invalid").build();
		new TransactionTemplate(transactions).executeWithoutResult(tx -> {
			publisher.sendSignupEvent(profile);
			publisher.sendSignupEvent(profile);
		});
		assertThat(jdbc.queryForList("""
				SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.eventId'))
				FROM member_outbox_events WHERE member_id=303
				""", String.class)).containsExactly("member-signup:303", "member-signup:303");
		// 브로커가 첫 레코드를 거절하게 만든 뒤 토픽 설정을 원래대로 돌린다.
		var topic = new ConfigResource(ConfigResource.Type.TOPIC, "member-signup");
		try (var admin = Admin.create(Map.of("bootstrap.servers", broker.getBrokersAsString()))) {
			var limit = new AlterConfigOp(new ConfigEntry("max.message.bytes", "1024"), AlterConfigOp.OpType.SET);
			var reset = new AlterConfigOp(new ConfigEntry("max.message.bytes", null), AlterConfigOp.OpType.DELETE);
			admin.incrementalAlterConfigs(Map.of(topic, List.of(limit))).all().get(10, TimeUnit.SECONDS);
			try {
				dispatch();
			} finally {
				admin.incrementalAlterConfigs(Map.of(topic, List.of(reset))).all().get(10, TimeUnit.SECONDS);
			}
		}
		String states = "SELECT status FROM member_outbox_events WHERE member_id=303 ORDER BY id";
		assertThat(jdbc.queryForList(states, String.class)).containsExactly("PENDING", "PENDING");
		assertThat(jdbc.queryForObject("SELECT SUM(retry_count) FROM member_outbox_events WHERE member_id=303", Integer.class)).isOne();
		jdbc.update("UPDATE member_outbox_events SET available_at=? WHERE member_id=303", LocalDateTime.now().minusMinutes(1));
		dispatch();
		assertThat(jdbc.queryForList(states, String.class)).containsExactly("PUBLISHED", "PENDING");
		dispatch();
		assertThat(jdbc.queryForList(states, String.class)).containsExactly("PUBLISHED", "PUBLISHED");
		var props = KafkaTestUtils.consumerProps("signup-probe", "false", broker);
		try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props,
				new StringDeserializer(), new StringDeserializer()).createConsumer()) {
			broker.consumeFromAnEmbeddedTopic(consumer, "member-signup");
			var forMember = new ArrayList<ConsumerRecord<String, String>>();
			long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
			while (forMember.size() < 2 && System.nanoTime() < deadline) {
				for (var record : consumer.poll(Duration.ofMillis(200))) {
					if (record.key().equals("303")) forMember.add(record);
				}
			}
			assertThat(forMember).hasSize(2).allSatisfy(record -> {
				assertThat(record.key()).isEqualTo("303");
				var payload = new ObjectMapper().readTree(record.value());
				assertThat(payload.path("email").asText()).isEqualTo(profile.email());
				assertThat(payload.path("eventId").asText()).isEqualTo("member-signup:303");
			});
		}
	}


	@Test
	@DisplayName("같은 회원의 이벤트는 memberId 키로 같은 파티션에 발행 순서대로 쌓인다")
	void sameMemberEventsKeepOrderInOnePartition() throws Exception {
		ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

		// 회원 A: 갱신 3건 후 탈퇴 1건, 회원 B: 갱신 2건
		new TransactionTemplate(transactions).executeWithoutResult(tx -> {
			publisher.sendProfileUpdatedMatchingEvent(profileEvent(MEMBER_A, 1L));
			publisher.sendProfileUpdatedMatchingEvent(profileEvent(MEMBER_A, 2L));
			publisher.sendProfileUpdatedMatchingEvent(profileEvent(MEMBER_A, 3L));
			publisher.sendWithdrawEvent(MEMBER_A, "a@test.com");
			publisher.sendProfileUpdatedMatchingEvent(profileEvent(MEMBER_B, 1L));
			publisher.sendProfileUpdatedMatchingEvent(profileEvent(MEMBER_B, 2L));
		});
		for (int i = 0; i < 3; i++) dispatch();

		List<ConsumerRecord<String, String>> records = consumeAll(broker, 6);

		// 1) 모든 레코드에 memberId 가 키로 실려 있다
		List<ConsumerRecord<String, String>> withdrawRecords = byTopic(records, "member-withdraw");
		List<ConsumerRecord<String, String>> profileRecords = byTopic(records, "profile-updates");

		assertThat(withdrawRecords).hasSize(1);
		assertThat(withdrawRecords.get(0).key()).isEqualTo(String.valueOf(MEMBER_A));
		assertThat(objectMapper.readTree(withdrawRecords.get(0).value()).path("eventId").asText())
			.isEqualTo("member-withdraw:" + MEMBER_A);
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
