package com.comatching.common.config.kafka;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;

import com.comatching.common.exception.BusinessException;

/**
 * DLT 재적재 검증.
 *
 * 재적재의 값어치는 "고친 뒤 되돌리면 평소 경로로 다시 처리되는가"에 있으므로
 * 적재 → 원인 수정 → 재적재 → 정상 소비의 전체 사이클을 실제 브로커로 돈다.
 *
 * 확인하는 것
 *  1) 원인을 고친 뒤 재적재하면 메시지가 원본 토픽을 거쳐 리스너에서 처리된다
 *  2) 키가 보존된다 (회원 단위 파티션 라우팅이 재적재 후에도 유지)
 *  3) 재적재는 커밋 오프셋을 남겨 같은 메시지를 두 번 되돌리지 않는다
 *  4) 소비를 선언하지 않은 토픽은 재적재를 거부한다 (오타 가드)
 */
@SpringBootTest(classes = {
	KafkaProducerConfig.class,
	KafkaConsumerConfig.class,
	KafkaDltRedriveIT.TestListeners.class
})
@EmbeddedKafka(
	partitions = 1,
	topics = {
		KafkaDltRedriveIT.REDRIVE_TOPIC,
		KafkaDltRedriveIT.REDRIVE_TOPIC + ".DLT"
	}
)
@TestPropertySource(properties = {
	"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
	"spring.kafka.consumer.group-id=kafka-dlt-redrive-it",
	"comatching.kafka.dlt-topics=" + KafkaDltRedriveIT.REDRIVE_TOPIC
})
@DisplayName("Kafka DLT 재적재")
@Tag("integration")
class KafkaDltRedriveIT {

	static final String REDRIVE_TOPIC = "redrive-it-topic";

	private static final String MEMBER_KEY = "7777";
	// 리스너가 ConsumerRecord 로 받으므로(키 검증 목적) 컨버터를 거치지 않은
	// 와이어 원문이 그대로 보인다.
	private static final String PAYLOAD_ON_WIRE = "\"redrive-me\"";

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	private KafkaDltRedriveService redriveService;

	@Autowired
	private TestListeners listeners;

	@Autowired
	private EmbeddedKafkaBroker broker;

	@Test
	@DisplayName("원인 수정 후 재적재하면 평소 소비 경로로 다시 처리되고, 두 번 되돌리지 않는다")
	void redriveReplaysThroughNormalPathExactlyOnce() {
		kafkaTemplate.send(REDRIVE_TOPIC, MEMBER_KEY, PAYLOAD_ON_WIRE);

		// 재시도 소진 → DLT 적재까지 기다린다
		ConsumerRecord<String, String> dltRecord = pollOne(REDRIVE_TOPIC + ".DLT", Duration.ofSeconds(30));
		assertThat(dltRecord).isNotNull();

		// 운영자가 원인을 고쳤다
		listeners.broken.set(false);

		KafkaDltRedriveService.RedriveResult result = redriveService.redrive(REDRIVE_TOPIC);
		assertThat(result.redriven()).isEqualTo(1);

		// 재발행분이 평소 경로(원본 토픽 → 리스너)로 처리된다. 키도 보존된다.
		Awaitility.await()
			.atMost(30, TimeUnit.SECONDS)
			.untilAsserted(() -> assertThat(listeners.processed).contains(MEMBER_KEY + ":" + PAYLOAD_ON_WIRE));

		// 커밋 오프셋이 남아 같은 메시지를 다시 되돌리지 않는다
		assertThat(redriveService.redrive(REDRIVE_TOPIC).redriven()).isZero();
	}

	@Test
	@DisplayName("소비를 선언하지 않은 토픽은 재적재를 거부한다")
	void rejectsUndeclaredTopic() {
		assertThatThrownBy(() -> redriveService.redrive("no-such-topic"))
			.isInstanceOf(BusinessException.class);
	}

	private ConsumerRecord<String, String> pollOne(String topic, Duration timeout) {
		Map<String, Object> props = new HashMap<>();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
		props.put(ConsumerConfig.GROUP_ID_CONFIG, "redrive-probe-" + topic);
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

		try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
			props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {

			consumer.subscribe(List.of(topic));
			ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, timeout, 1);
			return records.isEmpty() ? null : records.iterator().next();
		}
	}

	@EnableKafka
	@Configuration(proxyBeanMethods = false)
	static class TestListeners {

		final AtomicBoolean broken = new AtomicBoolean(true);
		final AtomicInteger attempts = new AtomicInteger();
		final List<String> processed = new CopyOnWriteArrayList<>();

		@KafkaListener(topics = REDRIVE_TOPIC, groupId = "redrive-it-listener")
		void consume(ConsumerRecord<String, String> record) {
			attempts.incrementAndGet();
			// 운영자가 원인을 고치기 전까지는 항상 실패한다 - DB 장애 등 지속
			// 장애가 고쳐지는 시점을 broken 플래그로 흉내낸다.
			if (broken.get()) {
				throw new IllegalStateException("아직 고쳐지지 않은 장애");
			}
			processed.add(record.key() + ":" + record.value());
		}
	}
}
