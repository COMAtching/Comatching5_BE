package com.comatching.matching.infra.kafka;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.comatching.common.config.kafka.KafkaConsumerConfig;
import com.comatching.common.config.kafka.KafkaDltTopicConfig;
import com.comatching.common.config.kafka.KafkaEventRoute;
import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.common.dto.event.member.MemberWithdrawnEvent;
import com.comatching.matching.domain.service.CandidateService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.common.TopicPartition;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 실제 리스너의 YAML group-id 해석, 브로커 협상 전략, 6개 파티션 소비를 확인한다. */
@SpringBootTest(classes = CandidateConsumerGroupIT.Config.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=com.comatching.common.config.kafka.EventValueSerializer",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.enable-auto-commit=false",
        "spring.kafka.listener.ack-mode=record",
        "comatching.kafka.dlt-routes=CANDIDATE_WITHDRAW,CANDIDATE_PROFILE",
        "comatching.kafka.dlt-alert-webhook-url="
    })
@EmbeddedKafka(partitions = 3, topics = {"member-withdraw", "profile-updates"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class CandidateConsumerGroupIT {
    static final String GROUP = "matching-candidate-sync-group";

    @Autowired EmbeddedKafkaBroker broker;
    @Autowired KafkaTemplate<String, Object> template;
    @MockitoBean CandidateService candidates;

    @Test
    @DisplayName("두 리스너의 소비자 6개가 같은 새 그룹에서 cooperative-sticky로 배정된다")
    void bothListenersShareCooperativeGroup() {
        try (AdminClient admin = admin()) {
            Awaitility.await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
                ConsumerGroupDescription group = admin.describeConsumerGroups(List.of(GROUP))
                    .all().get(5, TimeUnit.SECONDS).get(GROUP);
                assertThat(group.partitionAssignor()).isEqualTo("cooperative-sticky");
                assertThat(group.members()).hasSize(6);
                assertThat(group.members()).allSatisfy(member ->
                    assertThat(member.assignment().topicPartitions()).isNotEmpty());
                assertThat(group.members().stream().flatMap(member -> member.assignment().topicPartitions().stream()))
                    .containsExactlyInAnyOrder(
                        new TopicPartition("member-withdraw", 0), new TopicPartition("member-withdraw", 1),
                        new TopicPartition("member-withdraw", 2), new TopicPartition("profile-updates", 0),
                        new TopicPartition("profile-updates", 1), new TopicPartition("profile-updates", 2),
                        new TopicPartition(KafkaEventRoute.ReplayTopics.CANDIDATE_WITHDRAW, 0),
                        new TopicPartition(KafkaEventRoute.ReplayTopics.CANDIDATE_PROFILE, 0));
                assertThat(admin.listConsumerGroups().all().get(5, TimeUnit.SECONDS))
                    .noneMatch(existing -> "matching-service-group".equals(existing.groupId()));
            });
        }
    }

    @Test
    @DisplayName("두 토픽의 세 파티션을 각각 처리하고 새 그룹에 offset을 커밋한다")
    void consumesBothTopicsAndCommitsAllPartitions() throws Exception {
        Map<TopicPartition, Long> expectedOffsets = new HashMap<>();
        LocalDateTime withdrawnAt = LocalDateTime.of(2026, 11, 19, 12, 0);
        for (int partition = 0; partition < 3; partition++) {
            long memberId = 500L + partition;
            var withdrawal = new MemberWithdrawnEvent(memberId, "test@example.invalid", withdrawnAt);
            var profile = ProfileUpdatedMatchingEvent.builder().memberId(memberId).profileId(memberId + 100).build();
            for (var message : Map.<String, Object>of("member-withdraw", withdrawal, "profile-updates", profile).entrySet()) {
                var metadata = template.send(message.getKey(), partition, Long.toString(memberId), message.getValue())
                    .get(10, TimeUnit.SECONDS).getRecordMetadata();
                expectedOffsets.put(new TopicPartition(metadata.topic(), metadata.partition()), metadata.offset() + 1);
            }
        }
        try (AdminClient admin = admin()) {
            Awaitility.await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
                var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS);
                expectedOffsets.forEach((partition, next) -> {
                    assertThat(offsets.get(partition)).isNotNull();
                    assertThat(offsets.get(partition).offset()).isEqualTo(next);
                });
            });
        }
        for (long memberId = 500; memberId < 503; memberId++) {
            verify(candidates).removeCandidate(memberId, withdrawnAt);
            verify(candidates).upsertCandidate(ProfileUpdatedMatchingEvent.builder()
                .memberId(memberId).profileId(memberId + 100).build());
        }
        verifyNoMoreInteractions(candidates);
    }

    private AdminClient admin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    @Import({KafkaConsumerConfig.class, KafkaDltTopicConfig.class, MatchingMemberEventConsumer.class, ProfileEventConsumer.class})
    @ImportAutoConfiguration(KafkaAutoConfiguration.class)
    static class Config { }
}
