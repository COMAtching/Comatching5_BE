package com.comatching.common.config.kafka;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.comatching.common.exception.BusinessException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;

/** 실제 공통 설정으로 그룹별 DLT 격리, replay 재실패, DLT offset 진행을 검증한다. */
@SpringBootTest(classes = KafkaDltRedriveIT.Listeners.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=com.comatching.common.config.kafka.EventValueSerializer",
        "spring.kafka.consumer.group-id=deliberately-not-the-actual-listener-group",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.enable-auto-commit=false",
        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.listener.ack-mode=record",
        "comatching.kafka.dlt-routes=CANDIDATE_WITHDRAW,WITHDRAW_MAIL",
        "comatching.kafka.replication-factor=3",
        "comatching.kafka.dlt-alert-webhook-url="
    })
@EmbeddedKafka(count = 3, partitions = 3, topics = "member-withdraw",
    brokerProperties = {"min.insync.replicas=2", "offsets.topic.replication.factor=3"})
@org.junit.jupiter.api.Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaDltRedriveIT {
    @Autowired KafkaTemplate<String, Object> template;
    @Autowired KafkaDltRedriveService redrive;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired Listeners listeners;

    @Test
    void dltAndReplayUseTheConfiguredReplicationFactor() {
        var route = KafkaEventRoute.CANDIDATE_WITHDRAW;
        try (var consumer = probe()) {
            for (String topic : List.of(route.dltTopic(), route.replayTopic())) {
                assertThat(consumer.partitionsFor(topic)).singleElement()
                    .satisfies(partition -> assertThat(partition.replicas()).hasSize(3));
            }
        }
    }

    @Test
    void replayTargetsOnlyFailedGroupAndReturnsToSameDlt() throws Exception {
        var route = KafkaEventRoute.CANDIDATE_WITHDRAW;
        var sent = template.send(route.sourceTopic(), "42", "\"event-42\"").get(10, TimeUnit.SECONDS);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(end(route.dltTopic())).isEqualTo(1);
            assertThat(listeners.mailCalls).hasValue(1);
        });
        assertThat(listeners.matchingCalls).hasValue(4);
        assertDltRecord(route, route.sourceTopic(), sent.getRecordMetadata().offset());

        // 원인을 아직 고치지 않은 재처리도 시작 시점 DLT까지만 읽고 종료한다.
        assertThat(redrive.redrive(route).redriven()).isEqualTo(1);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(end(route.dltTopic())).isEqualTo(2);
            assertThat(listeners.matchingCalls).hasValue(8);
        });
        assertThat(end(route.sourceTopic())).isEqualTo(1); // 공용 원본에 새 레코드가 추가되지 않았다.
        assertThat(end(KafkaEventRoute.WITHDRAW_MAIL.dltTopic())).isZero();
        assertThat(listeners.mailCalls).hasValue(1);

        listeners.broken.set(false);
        assertThat(redrive.redrive(route).redriven()).isEqualTo(1); // 두 번째 DLT 건만 읽는다.
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(listeners.processed).containsExactly("42:\"event-42\""));

        assertThat(listeners.matchingCalls).hasValue(9);
        assertThat(listeners.mailCalls).hasValue(1);
        assertThat(end(route.sourceTopic())).isEqualTo(1);
        assertThat(end(route.replayTopic())).isEqualTo(2);
        assertThat(redrive.redrive(route).redriven()).isZero();
    }

    @Test
    void rejectsUndeclaredRouteAndUnregisteredConsumerGroup() {
        assertThatThrownBy(() -> redrive.redrive(KafkaEventRoute.CANDIDATE_PROFILE))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> KafkaEventRoute.forRecord("member-withdraw", "unknown-group"))
            .isInstanceOf(IllegalStateException.class);
        for (var route : KafkaEventRoute.values()) {
            assertThat(KafkaEventRoute.forRecord(route.replayTopic(), route.groupId())).isSameAs(route);
        }
    }

    private long end(String topic) {
        try (var consumer = probe()) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                .map(info -> new TopicPartition(topic, info.partition())).toList();
            return consumer.endOffsets(partitions).values().stream().mapToLong(Long::longValue).sum();
        }
    }

    private void assertDltRecord(KafkaEventRoute route, String originalTopic, long originalOffset) {
        try (var consumer = probe()) {
            var partition = new TopicPartition(route.dltTopic(), 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            var records = org.springframework.kafka.test.utils.KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 1);
            assertThat(records.count()).isEqualTo(1);
            var record = records.iterator().next();
            assertThat(record.key()).isEqualTo("42");
            assertThat(record.value()).isEqualTo("\"event-42\"");
            assertThat(new String(record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(), StandardCharsets.UTF_8))
                .isEqualTo(originalTopic);
            assertThat(java.nio.ByteBuffer.wrap(record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value()).getLong())
                .isEqualTo(originalOffset);
        }
    }

    private KafkaConsumer<String, String> probe() {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false), new StringDeserializer(), new StringDeserializer());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    @Import({KafkaConsumerConfig.class, KafkaDltTopicConfig.class})
    @ImportAutoConfiguration(KafkaAutoConfiguration.class)
    static class Listeners {
        final AtomicBoolean broken = new AtomicBoolean(true);
        final AtomicInteger matchingCalls = new AtomicInteger();
        final AtomicInteger mailCalls = new AtomicInteger();
        final List<String> processed = new CopyOnWriteArrayList<>();

        @KafkaListener(topics = {"member-withdraw", KafkaEventRoute.ReplayTopics.CANDIDATE_WITHDRAW},
            groupId = "matching-candidate-sync-group")
        void matching(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record) {
            matchingCalls.incrementAndGet();
            if (broken.get()) throw new IllegalStateException("simulated matching failure");
            processed.add(record.key() + ":" + record.value());
        }

        @KafkaListener(topics = {"member-withdraw", KafkaEventRoute.ReplayTopics.WITHDRAW_MAIL},
            groupId = "notification-withdraw-group")
        void mail(String event) {
            mailCalls.incrementAndGet();
        }
    }
}
