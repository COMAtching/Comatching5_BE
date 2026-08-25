package com.comatching.common.config.kafka;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.KafkaOperations;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * DLT 재적재(re-drive) - 원인을 고친 뒤 쌓인 메시지를 원본 토픽으로 되돌린다.
 *
 * 되돌리는 곳이 리스너가 아니라 원본 토픽인 이유: 재발행하면 평소의 소비
 * 경로(재시도 → 실패 시 DLT → 알림)를 그대로 다시 탄다. 원인이 안 고쳐진
 * 메시지는 다시 DLT 로 돌아와 다시 알림이 오므로, 재처리 전용 실패 경로를
 * 따로 만들 필요가 없다.
 *
 * == 동작 방식 ==
 * 전용 그룹(<그룹>-dlt-redrive)으로 <토픽>.DLT 를 커밋 오프셋부터 읽어
 * 원본 토픽에 키·값 그대로 재발행하고 커밋한다. 시작 시점의 끝 오프셋을
 * 스냅샷으로 잡고 거기까지만 처리한다 - 재발행분이 곧바로 다시 실패해 DLT 에
 * 돌아오는 경우 스냅샷이 없으면 같은 메시지를 무한히 쫓아가게 된다.
 *
 * == 보장 수준 ==
 * 재발행 완료 후 커밋이므로 at-least-once 다. 커밋 직전에 죽으면 다음 호출이
 * 같은 메시지를 한 번 더 되돌리지만, 컨슈머들이 멱등(tombstone 가드, upsert)
 * 이라 안전하다. 반대(커밋 후 재발행)는 유실이라 선택지가 아니다.
 *
 * == 운영 제약 ==
 * profile-updates 재적재는 tombstone TTL(2일) 안에 해야 한다. 그 뒤에는
 * tombstone 이 지워져 탈퇴 회원의 늦은 갱신이 후보를 부활시킬 수 있다.
 * 재발행은 현재 트래픽과 순서가 섞이므로, 같은 회원의 더 새로운 이벤트가
 * 이미 처리된 뒤라면 옛 이벤트가 덮어쓸 수 있다(갱신 이벤트에 버전이 없는
 * 한계 - KAFKA_PARTITIONING.md "남은 한계" 참고).
 */
@Slf4j
public class KafkaDltRedriveService {

	private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);
	private static final long PUBLISH_TIMEOUT_SECONDS = 10;

	// 재발행 시 걷어내는 진단 헤더. 남겨두면 다음 실패 때 스택트레이스까지
	// 원본 토픽을 왕복하며 불어난다. __TypeId__ 같은 역직렬화 헤더는 보존한다.
	private static final List<String> DIAGNOSTIC_HEADER_PREFIXES = List.of("kafka_dlt-", "kafka_exception");

	private final String bootstrapServers;
	private final String redriveGroupId;
	private final KafkaOperations<String, byte[]> bytesTemplate;
	private final List<String> redrivableTopics;

	public KafkaDltRedriveService(String bootstrapServers, String consumerGroupId,
		KafkaOperations<String, byte[]> bytesTemplate, List<String> redrivableTopics) {
		this.bootstrapServers = bootstrapServers;
		this.redriveGroupId = consumerGroupId + "-dlt-redrive";
		this.bytesTemplate = bytesTemplate;
		this.redrivableTopics = redrivableTopics.stream().map(String::trim).toList();
	}

	public record RedriveResult(String topic, String dltTopic, long redriven) {
	}

	/**
	 * synchronized: 같은 인스턴스에서의 동시 호출이 커밋을 서로 밟지 않게 한다.
	 * 인스턴스가 여러 대면 중복 재발행이 가능하지만 at-least-once 안에서 무해하다.
	 */
	public synchronized RedriveResult redrive(String sourceTopic) {
		if (!redrivableTopics.contains(sourceTopic)) {
			// 오타로 임의 토픽에 재적재 컨슈머가 붙는 것을 막는다. 허용 목록은
			// 이 서비스가 소비를 선언한 토픽(comatching.kafka.dlt-topics)이다.
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE,
				"재적재 가능한 토픽이 아닙니다: " + sourceTopic + " (허용: " + redrivableTopics + ")");
		}

		String dltTopic = sourceTopic + KafkaConsumerConfig.DLT_SUFFIX;
		long redriven = 0;

		try (Consumer<String, byte[]> consumer = createConsumer()) {
			List<TopicPartition> partitions = consumer.partitionsFor(dltTopic).stream()
				.map(info -> new TopicPartition(dltTopic, info.partition()))
				.toList();
			consumer.assign(partitions);

			Map<TopicPartition, Long> endSnapshot = consumer.endOffsets(partitions);

			while (hasRemaining(consumer, endSnapshot)) {
				ConsumerRecords<String, byte[]> records = consumer.poll(POLL_TIMEOUT);
				for (ConsumerRecord<String, byte[]> record : records) {
					// 스냅샷 이후 적재분(재발행 실패 회귀 포함)은 다음 호출 몫이다
					if (record.offset() >= endSnapshot.get(new TopicPartition(record.topic(), record.partition()))) {
						continue;
					}
					republish(sourceTopic, record);
					redriven++;
				}
				commitUpToSnapshot(consumer, endSnapshot);
			}
		}

		log.info("DLT 재적재 완료: {} -> {} ({}건)", dltTopic, sourceTopic, redriven);
		return new RedriveResult(sourceTopic, dltTopic, redriven);
	}

	private Consumer<String, byte[]> createConsumer() {
		Map<String, Object> config = new HashMap<>();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		config.put(ConsumerConfig.GROUP_ID_CONFIG, redriveGroupId);
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		return new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer());
	}

	private void republish(String sourceTopic, ConsumerRecord<String, byte[]> record) {
		RecordHeaders headers = new RecordHeaders();
		for (Header header : record.headers()) {
			if (DIAGNOSTIC_HEADER_PREFIXES.stream().noneMatch(prefix -> header.key().startsWith(prefix))) {
				headers.add(header);
			}
		}

		ProducerRecord<String, byte[]> outbound =
			new ProducerRecord<>(sourceTopic, null, record.key(), record.value(), headers);
		try {
			// 브로커 확인을 기다린 뒤에야 커밋 대상이 된다. 확인 전에 커밋하면
			// 재발행이 실패했을 때 그 메시지가 DLT 에서도 사라진다.
			bytesTemplate.send(outbound).get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("DLT 재발행 중단: " + dltPosition(record), e);
		} catch (Exception e) {
			throw new IllegalStateException("DLT 재발행 실패: " + dltPosition(record), e);
		}
	}

	private boolean hasRemaining(Consumer<String, byte[]> consumer, Map<TopicPartition, Long> endSnapshot) {
		return endSnapshot.entrySet().stream()
			.anyMatch(entry -> consumer.position(entry.getKey()) < entry.getValue());
	}

	private void commitUpToSnapshot(Consumer<String, byte[]> consumer, Map<TopicPartition, Long> endSnapshot) {
		Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
		endSnapshot.forEach((partition, end) ->
			offsets.put(partition, new OffsetAndMetadata(Math.min(consumer.position(partition), end))));
		consumer.commitSync(offsets);
	}

	private String dltPosition(ConsumerRecord<String, byte[]> record) {
		return record.topic() + " p" + record.partition() + "@" + record.offset() + " key=" + record.key();
	}
}
