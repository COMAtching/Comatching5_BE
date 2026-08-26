package com.comatching.common.config.kafka;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;

/**
 * 재시도·DLT 정책 검증.
 *
 * 이 정책의 값어치는 "실패했을 때 무슨 일이 벌어지는가"에 있으므로 실제 브로커로
 * 확인한다. 목으로는 몇 번 재시도했는지도, 메시지가 어디로 갔는지도 볼 수 없다.
 *
 * 확인하는 것
 *  1) 계속 실패하면 정해진 횟수만큼 재시도한 뒤 <토픽>.DLT 로 옮겨진다 (조용한 유실 아님)
 *  2) 일시적 실패는 재시도로 회복되고 DLT 로 가지 않는다
 *  3) 원본 키와 위치 헤더가 DLT 에 보존된다 (어느 회원 건인지 추적 가능)
 *  4) DLT 이동 시에만 알림이 발화한다 (회복된 실패는 알리지 않는다)
 */
@SpringBootTest(classes = {
	KafkaProducerConfig.class,
	KafkaConsumerConfig.class,
	KafkaRetryAndDltIT.TestListeners.class
})
@EmbeddedKafka(
	partitions = 1,
	topics = {
		KafkaRetryAndDltIT.ALWAYS_FAILS_TOPIC,
		KafkaRetryAndDltIT.ALWAYS_FAILS_TOPIC + ".DLT",
		KafkaRetryAndDltIT.RECOVERS_TOPIC,
		KafkaRetryAndDltIT.RECOVERS_TOPIC + ".DLT"
	}
)
@TestPropertySource(properties = {
	"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
	"spring.kafka.consumer.group-id=kafka-retry-dlt-it"
})
@DisplayName("Kafka 재시도·DLT 정책")
class KafkaRetryAndDltIT {

	static final String ALWAYS_FAILS_TOPIC = "retry-it-always-fails";
	static final String RECOVERS_TOPIC = "retry-it-recovers";

	private static final String MEMBER_KEY = "4242";

	// String 경로는 StringJsonMessageConverter 를 거치므로 발행 값이 유효한 JSON 이어야
	// 한다. JSON 문자열 리터럴을 보내면 리스너는 따옴표가 벗겨진 값을 받고,
	// DLT 에는 원본 바이트가 그대로 실린다.
	private static final String PAYLOAD_ON_WIRE = "\"boom\"";
	private static final String PAYLOAD_AT_LISTENER = "boom";

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	private TestListeners listeners;

	@Autowired
	private EmbeddedKafkaBroker broker;

	@Test
	@DisplayName("계속 실패하면 재시도를 소진한 뒤 DLT 로 옮겨진다 - 조용히 사라지지 않는다")
	void exhaustedRetriesLandInDlt() {
		kafkaTemplate.send(ALWAYS_FAILS_TOPIC, MEMBER_KEY, PAYLOAD_ON_WIRE);

		ConsumerRecord<String, String> dltRecord =
			pollOne(ALWAYS_FAILS_TOPIC + ".DLT", Duration.ofSeconds(30));

		assertThat(dltRecord).isNotNull();
		assertThat(dltRecord.value()).isEqualTo(PAYLOAD_ON_WIRE);

		// 어느 회원 건인지 DLT 에서도 알 수 있어야 재처리가 가능하다
		assertThat(dltRecord.key()).isEqualTo(MEMBER_KEY);

		// 원본 위치가 헤더로 남는다
		assertThat(dltRecord.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC)).isNotNull();
		assertThat(new String(dltRecord.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value()))
			.isEqualTo(ALWAYS_FAILS_TOPIC);

		// 최초 1회 + 재시도 3회. 즉시 포기하지도, 무한히 매달리지도 않는다.
		assertThat(listeners.alwaysFailsAttempts.get()).isEqualTo(4);

		// DLT 이동은 사람에게 알려진다. 적재를 이틀(tombstone TTL) 안에
		// 알아채야 재처리가 의미 있기 때문이다.
		assertThat(listeners.alerts)
			.anySatisfy(alert -> assertThat(alert).contains(ALWAYS_FAILS_TOPIC + ".DLT"));
	}

	@Test
	@DisplayName("일시적 실패는 재시도로 회복되고 DLT 로 가지 않는다")
	void transientFailureRecoversWithoutDlt() {
		kafkaTemplate.send(RECOVERS_TOPIC, MEMBER_KEY, PAYLOAD_ON_WIRE);

		Awaitility.await()
			.atMost(30, TimeUnit.SECONDS)
			.untilAsserted(() -> assertThat(listeners.recovered).contains(PAYLOAD_AT_LISTENER));

		// 2번째 시도에서 성공했으므로 DLT 는 비어 있어야 하고, 알림도 없어야 한다.
		// 회복된 실패까지 알리면 알림이 늑대소년이 된다.
		assertThat(pollOne(RECOVERS_TOPIC + ".DLT", Duration.ofSeconds(5))).isNull();
		assertThat(listeners.alerts)
			.noneSatisfy(alert -> assertThat(alert).contains(RECOVERS_TOPIC));
	}

	private ConsumerRecord<String, String> pollOne(String topic, Duration timeout) {
		Map<String, Object> props = new HashMap<>();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
		props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-probe-" + topic);
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

		try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
			props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {

			consumer.subscribe(List.of(topic));
			ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, timeout, 1);
			return records.isEmpty() ? null : records.iterator().next();
		}
	}

	/**
	 * proxyBeanMethods = false 로 둔 이유는 CGLIB 프록시 없이 이 인스턴스 자체가
	 * 빈이 되게 하려는 것이다. 리스너가 올리는 카운터와 테스트가 읽는 카운터가
	 * 같은 객체여야 한다.
	 */
	@EnableKafka
	@Configuration(proxyBeanMethods = false)
	static class TestListeners {

		final AtomicInteger alwaysFailsAttempts = new AtomicInteger();
		final AtomicInteger recoversAttempts = new AtomicInteger();
		final List<String> recovered = new CopyOnWriteArrayList<>();
		final List<String> alerts = new CopyOnWriteArrayList<>();

		// 실제 웹훅 대신 전송 내용을 기록한다. HTTP 를 목으로 갈아끼우는 게 아니라
		// 전송 직전 지점(deliver)만 가로채므로 쿨다운·메시지 조립은 실물이 돈다.
		@Bean
		@Primary
		KafkaDltAlertNotifier recordingDltAlertNotifier() {
			return new KafkaDltAlertNotifier("http://recording-stub", "kafka-retry-dlt-it", Duration.ZERO) {
				@Override
				protected void deliver(String message) {
					alerts.add(message);
				}
			};
		}

		@KafkaListener(topics = ALWAYS_FAILS_TOPIC, groupId = "always-fails-group")
		void alwaysFails(String payload) {
			alwaysFailsAttempts.incrementAndGet();
			throw new IllegalStateException("의도적 실패: " + payload);
		}

		@KafkaListener(topics = RECOVERS_TOPIC, groupId = "recovers-group")
		void recoversOnRetry(String payload) {
			// 첫 시도만 실패시킨다. DB 순단 같은 일시적 실패를 흉내내는 것이다.
			if (recoversAttempts.incrementAndGet() == 1) {
				throw new IllegalStateException("일시적 실패: " + payload);
			}
			recovered.add(payload);
		}
	}
}
