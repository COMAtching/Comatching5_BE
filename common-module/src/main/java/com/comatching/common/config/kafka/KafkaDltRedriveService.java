package com.comatching.common.config.kafka;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.KafkaOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 그룹별 DLT → 해당 그룹 전용 replay. 시작할 때 있던 DLT 레코드까지만 재발행한다.
 * 처리 의미는 at-least-once다. replay 발행 후 DLT offset 커밋 전에 종료되면 중복 가능.
 * synchronized는 단일 인스턴스만 보호하므로 운영 재처리는 한 인스턴스에서 수행한다.
 */
@Slf4j
public class KafkaDltRedriveService {
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);
    private static final List<String> DIAGNOSTIC_PREFIXES = List.of("kafka_dlt-", "kafka_exception");
    private final Map<String, Object> consumerProperties;
    private final KafkaOperations<String, Object> kafkaTemplate;
    private final List<KafkaEventRoute> allowedRoutes;

    public KafkaDltRedriveService(Map<String, Object> consumerProperties,
                                  KafkaOperations<String, Object> kafkaTemplate, List<KafkaEventRoute> allowedRoutes) {
        this.consumerProperties = new HashMap<>(consumerProperties);
        this.kafkaTemplate = kafkaTemplate;
        this.allowedRoutes = List.copyOf(allowedRoutes);
    }

    public record RedriveResult(String sourceTopic, String groupId, String dltTopic,
                                String replayTopic, long redriven) {
    }

    public synchronized RedriveResult redrive(KafkaEventRoute route) {
        if (!allowedRoutes.contains(route)) {
            throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE,
                    "이 서비스에서 재처리할 수 없는 경로: " + route);
        }
        long redriven = 0;
        try (Consumer<String, byte[]> consumer = createConsumer(route)) {
            List<TopicPartition> partitions = consumer.partitionsFor(route.dltTopic()).stream()
                    .map(info -> new TopicPartition(route.dltTopic(), info.partition())).toList();
            consumer.assign(partitions);
            Map<TopicPartition, Long> endSnapshot = consumer.endOffsets(partitions);

            while (hasRemaining(consumer, endSnapshot)) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(POLL_TIMEOUT)) {
                    TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                    if (record.offset() >= endSnapshot.get(partition)) continue;

                    republish(route.replayTopic(), record);
                    // 해당 레코드의 replay 발행이 확인된 뒤에만 DLT 처리 위치를 넘긴다.
                    consumer.commitSync(Map.of(partition, new OffsetAndMetadata(record.offset() + 1)));
                    redriven++;
                }
            }
        }
        log.info("DLT 재처리: route={} dlt={} replay={} count={}",
                route, route.dltTopic(), route.replayTopic(), redriven);
        return new RedriveResult(route.sourceTopic(), route.groupId(), route.dltTopic(), route.replayTopic(), redriven);
    }

    private Consumer<String, byte[]> createConsumer(KafkaEventRoute route) {
        Map<String, Object> config = new HashMap<>(consumerProperties);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, route.groupId() + "." + route.name() + ".dlt-redrive");
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "dlt-redrive-" + route.name());
        config.remove(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        return new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer());
    }

    private void republish(String replayTopic, ConsumerRecord<String, byte[]> record) {
        RecordHeaders headers = new RecordHeaders();
        for (Header header : record.headers()) {
            if (DIAGNOSTIC_PREFIXES.stream().noneMatch(prefix -> header.key().startsWith(prefix))) {
                headers.add(header);
            }
        }
        // JSON을 다시 만들지 않는다. key·원본 바이트·업무 헤더(eventId 등)를 그대로 보존한다.
        ProducerRecord<String, Object> outbound =
                new ProducerRecord<>(replayTopic, null, record.key(), record.value(), headers);
        try {
            kafkaTemplate.send(outbound).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DLT 재처리 중단", e);
        } catch (Exception e) {
            throw new IllegalStateException("DLT → replay 발행 실패", e);
        }
    }

    private boolean hasRemaining(Consumer<String, byte[]> consumer, Map<TopicPartition, Long> snapshot) {
        return snapshot.entrySet().stream().anyMatch(entry -> consumer.position(entry.getKey()) < entry.getValue());
    }
}
