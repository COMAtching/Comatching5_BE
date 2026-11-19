package com.comatching.chat.domain.service.chatroom;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.TopicPartition;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.comatching.chat.domain.entity.ChatRoom;
import com.comatching.chat.domain.repository.ChatMessageRepository;
import com.comatching.chat.domain.repository.ChatRoomRepository;
import com.comatching.chat.domain.service.block.BlockService;
import com.comatching.chat.infra.client.MemberClient;
import com.comatching.chat.infra.kafka.MatchingConsumer;
import com.comatching.common.config.kafka.KafkaConsumerConfig;
import com.comatching.common.dto.event.matching.MatchingSuccessEvent;

/** 서비스 DB가 아닌 테스트 전용 MongoDB에서 실제 unique 충돌과 Kafka 중복 소비를 검증한다. */
@DataMongoTest(properties = {
    "spring.data.mongodb.auto-index-creation=true",
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
    "spring.kafka.producer.value-serializer=com.comatching.common.config.kafka.EventValueSerializer",
    "spring.kafka.producer.acks=all",
    "spring.kafka.producer.properties[enable.idempotence]=true",
    "spring.kafka.consumer.group-id=chat-service-group",
    "spring.kafka.consumer.auto-offset-reset=earliest",
    "spring.kafka.consumer.enable-auto-commit=false",
    "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
    "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
    "spring.kafka.listener.ack-mode=record"
})
@ContextConfiguration(classes = ChatRoomIdempotencyIT.Config.class)
@Import({MatchingConsumer.class, KafkaConsumerConfig.class})
@ImportAutoConfiguration(KafkaAutoConfiguration.class)
@EmbeddedKafka(partitions = 1, topics = {"matching-success-topic", "matching-success-topic.DLT"})
@Testcontainers
@Tag("integration")
@DisplayName("채팅방 실제 MongoDB 중복 방지·Kafka 소비")
class ChatRoomIdempotencyIT {

    @Container
    static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:7")
        .withExposedPorts(27017);

    @DynamicPropertySource
    static void mongodb(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () ->
            "mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017) + "/comatching_chat_outbox_test");
        registry.add("spring.data.mongodb.database", () -> "comatching_chat_outbox_test");
    }

    @Autowired ChatRoomRepository repository;
    @Autowired ChatRoomService chatRoomService;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired EmbeddedKafkaBroker broker;

    @BeforeEach
    void cleanCollection() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("엔티티 선언으로 matchingId의 실제 unique 인덱스가 생성된다")
    void createsUniqueMatchingIdIndex() {
        assertThat(mongoTemplate.indexOps(ChatRoom.class).getIndexInfo()).anySatisfy(index -> {
            assertThat(index.isUnique()).isTrue();
            assertThat(index.getIndexFields()).extracting(field -> field.getKey()).containsExactly("matchingId");
        });
    }

    @Test
    @DisplayName("같은 이벤트를 다시 처리해도 채팅방 ID와 방 개수가 유지된다")
    void repeatedEventKeepsExistingRoom() {
        MatchingSuccessEvent event = new MatchingSuccessEvent(501L, 10L, 20L);
        chatRoomService.createChatRoom(event);
        String originalId = repository.findByMatchingId(event.matchingId()).orElseThrow().getId();
        chatRoomService.createChatRoom(event);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByMatchingId(event.matchingId()).orElseThrow().getId()).isEqualTo(originalId);
    }

    @Test
    @DisplayName("두 작업자가 동시에 방이 없다고 조회해도 unique 충돌을 처리하고 방 하나만 남는다")
    void concurrentCreationRecoversFromActualDuplicateKey() throws Exception {
        MatchingSuccessEvent event = new MatchingSuccessEvent(502L, 10L, 20L);
        ChatRoomRepository racingRepository = mock(ChatRoomRepository.class, delegatesTo(repository));
        CyclicBarrier bothReadAbsent = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(invocation -> {
            var result = repository.findByMatchingId(event.matchingId());
            if (reads.incrementAndGet() <= 2) {
                assertThat(result).isEmpty();
                bothReadAbsent.await(5, TimeUnit.SECONDS);
            }
            return result;
        }).when(racingRepository).findByMatchingId(event.matchingId());
        ChatRoomServiceImpl service = service(racingRepository);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.createChatRoom(event));
            var second = executor.submit(() -> service.createChatRoom(event));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertThat(repository.count()).isEqualTo(1);
            assertThat(reads.get()).isEqualTo(3); // 두 사전 조회 + 충돌 후 기존 방 조회
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("서로 다른 offset의 중복 이벤트를 실제 Consumer가 처리하고 다음 offset을 커밋한다")
    void duplicateKafkaRecordsCreateOneRoomAndCommitBothOffsets() throws Exception {
        MatchingSuccessEvent event = new MatchingSuccessEvent(503L, 10L, 20L);
        var first = kafkaTemplate.send("matching-success-topic", "503", event).get(10, TimeUnit.SECONDS);
        var second = kafkaTemplate.send("matching-success-topic", "503", event).get(10, TimeUnit.SECONDS);
        assertThat(second.getRecordMetadata().offset()).isGreaterThan(first.getRecordMetadata().offset());
        TopicPartition partition = new TopicPartition("matching-success-topic", second.getRecordMetadata().partition());
        try (AdminClient admin = AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var offsets = admin.listConsumerGroupOffsets("chat-service-group")
                    .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                assertThat(offsets.get(partition)).isNotNull();
                assertThat(offsets.get(partition).offset()).isEqualTo(second.getRecordMetadata().offset() + 1);
                assertThat(repository.count()).isEqualTo(1);
                ChatRoom room = repository.findByMatchingId(503L).orElseThrow();
                assertThat(room.getInitiatorUserId()).isEqualTo(10L);
                assertThat(room.getTargetUserId()).isEqualTo(20L);
            });
        }
    }

    private static ChatRoomServiceImpl service(ChatRoomRepository repository) {
        return new ChatRoomServiceImpl(repository, mock(ChatMessageRepository.class),
            mock(BlockService.class), mock(MemberClient.class));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ChatRoom.class)
    @EnableMongoRepositories(basePackageClasses = ChatRoomRepository.class)
    static class Config {
        @Bean ChatRoomService chatRoomService(ChatRoomRepository repository) {
            return service(repository);
        }
    }
}
