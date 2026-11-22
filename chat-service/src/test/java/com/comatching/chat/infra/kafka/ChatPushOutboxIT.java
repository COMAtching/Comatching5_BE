package com.comatching.chat.infra.kafka;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.comatching.chat.domain.dto.ChatMessageRequest;
import com.comatching.chat.domain.entity.ChatMessage;
import com.comatching.chat.domain.entity.ChatRoom;
import com.comatching.chat.domain.entity.UserBlock;
import com.comatching.chat.domain.enums.MessageType;
import com.comatching.chat.domain.repository.ChatMessageRepository;
import com.comatching.chat.domain.repository.ChatRoomRepository;
import com.comatching.chat.domain.repository.UserBlockRepository;
import com.comatching.chat.domain.service.block.BlockService;
import com.comatching.chat.domain.service.chat.ChatServiceImpl;
import com.comatching.chat.global.config.MongoConfig;
import com.comatching.chat.infra.controller.InternalChatPushController;
import com.comatching.common.dto.event.chat.ChatMessageEvent;
import com.comatching.common.filter.InternalApiAuthenticationFilter;
import com.comatching.common.service.S3Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataMongoTest(properties = {"spring.data.mongodb.auto-index-creation=true",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"})
@ContextConfiguration(classes = ChatPushOutboxIT.Config.class)
@ImportAutoConfiguration(KafkaAutoConfiguration.class)
@EmbeddedKafka(partitions = 1, topics = ChatPushOutboxIT.TOPIC)
@Testcontainers
@DirtiesContext
@Tag("integration")
class ChatPushOutboxIT {
    static final String TOPIC = "chat-message-sent";

    @Configuration
    @EntityScan(basePackageClasses = ChatMessage.class)
    @EnableMongoRepositories(basePackageClasses = ChatMessageRepository.class)
    @Import({MongoConfig.class, ChatServiceImpl.class, ChatPushOutboxDispatcher.class,
            ChatEventProducer.class, InternalChatPushController.class})
    static class Config {}

    @Container static final GenericContainer<?> mongo = new GenericContainer<>("mongo:7").withExposedPorts(27017);
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.data.mongodb.uri", () ->
                "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/chat_push_test");
        properties.add("spring.data.mongodb.database", () -> "chat_push_test");
    }

    @Autowired ChatServiceImpl chat;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatRoomRepository rooms;
    @Autowired UserBlockRepository blocks;
    @Autowired MongoTemplate db;
    @Autowired ChatPushOutboxDispatcher worker;
    @Autowired InternalChatPushController push;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired KafkaTemplate<String, Object> kafka;
    @MockitoSpyBean ChatEventProducer producer;
    @MockitoBean BlockService blockService;
    @MockitoBean S3Service s3;
    private String roomId;

    @BeforeEach
    void prepareRoom() {
        messages.deleteAll();
        rooms.deleteAll();
        blocks.deleteAll();
        var room = ChatRoom.builder().matchingId(100L).initiatorUserId(10L).targetUserId(20L).build();
        room.updateLastReadAt(20L, LocalDateTime.now().minusMinutes(1));
        roomId = rooms.save(room).getId();
    }

    @Test
    void persistedImageSurvivesBrokerFailureAndLostAckAndKeepsItsKeyAndId() throws Exception {
        when(s3.getFileUrl("photo")).thenReturn("https://example.invalid/photo");
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")))
                .doAnswer(call -> {
                    ChatMessageEvent event = call.getArgument(0);
                    return kafka.send(TOPIC, event.roomId(), event).thenApply(ack -> {
                        throw new IllegalStateException("broker accepted message but ACK was lost");
                    });
                }).doCallRealMethod().when(producer).send(any());

        String id = send(MessageType.IMAGE, "photo");
        assertThat(messages.findById(id).orElseThrow().getContent()).isEqualTo("https://example.invalid/photo");
        assertThat(outbox(id).status()).isEqualTo("PENDING");
        verify(producer, never()).send(any());

        worker.dispatch();
        assertThat(outbox(id).availableAt()).isAfter(Instant.now());
        worker.dispatch();
        verify(producer).send(any());
        makeDue(id);
        worker.dispatch();
        assertThat(outbox(id).status()).isEqualTo("PENDING");
        makeDue(id);
        worker.dispatch();
        assertThat(outbox(id).status()).isEqualTo("PUBLISHED");
        assertThat(outbox(id).availableAt()).isNull();
        assertThat(outbox(id).claimToken()).isNull();
        worker.dispatch();
        verify(producer, times(3)).send(any());

        var props = KafkaTestUtils.consumerProps(UUID.randomUUID().toString(), "false", broker);
        try (var probe = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(probe, TOPIC);
            var records = KafkaTestUtils.getRecords(probe, Duration.ofSeconds(10), 2);
            assertThat(records.records(TOPIC)).filteredOn(record -> roomId.equals(record.key()))
                    .hasSize(2).allSatisfy(record -> {
                        var event = new ObjectMapper().readValue(record.value(), ChatMessageEvent.class);
                        assertThat(event.eventId()).isEqualTo("chat-message:" + id);
                        assertThat(event.targetUserId()).isEqualTo(20L);
                        assertThat(event.content()).isEqualTo("사진을 보냈습니다.");
                    });
        }
    }

    @Test
    void oldWorkerCannotUndoNewWorkersPublishedState() throws Exception {
        String id = send(MessageType.TALK, "hello");
        var entered = new CountDownLatch(1);
        var firstAck = new CompletableFuture<SendResult<String, Object>>();
        doAnswer(call -> {
            entered.countDown();
            return firstAck;
        }).doCallRealMethod().when(producer).send(any());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(worker::dispatch);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(outbox(id).status()).isEqualTo("PROCESSING");
            worker.dispatch();
            verify(producer).send(any());
            makeDue(id);
            worker.dispatch();
            firstAck.completeExceptionally(new IllegalStateException("old worker failed"));
            first.get(5, TimeUnit.SECONDS);
            assertThat(outbox(id).status()).isEqualTo("PUBLISHED");
            verify(producer, times(2)).send(any());
        } finally {
            firstAck.cancel(true);
            pool.shutdownNow();
        }
    }

    @Test
    void internalEndpointChecksRecipientReadBoundaryAndCurrentBlock() throws Exception {
        String id = send(MessageType.TALK, "hello");
        var mvc = MockMvcBuilders.standaloneSetup(push)
                .addFilters(new InternalApiAuthenticationFilter("test-token", new ObjectMapper())).build();
        var path = "/api/internal/chat/messages/{id}/push-eligible";
        mvc.perform(get(path, id).param("targetUserId", "20")).andExpect(status().isUnauthorized());
        mvc.perform(get(path, id).param("targetUserId", "20").header("X-Internal-Token", "test-token"))
                .andExpect(status().isOk()).andExpect(content().string("true"));
        assertThat(push.eligible(id, 10L)).isFalse();
        blocks.save(UserBlock.builder().blockerUserId(20L).blockedUserId(10L).build());
        assertThat(push.eligible(id, 20L)).isFalse();
        blocks.deleteAll();
        rooms.touchLastReadAt(roomId, 20L, messages.findById(id).orElseThrow().getCreatedAt());
        assertThat(push.eligible(id, 20L)).isFalse();
    }

    @Test
    void oldMessagesSystemMessagesAndBlockedMessagesNeverBecomeDue() {
        String system = send(MessageType.SYSTEM, "system");
        when(blockService.isBlocked(20L, 10L)).thenReturn(true);
        String blocked = send(MessageType.TALK, "blocked");
        var historical = messages.save(ChatMessage.builder().roomId(roomId).senderId(10L)
                .content("before outbox").type(MessageType.TALK).build());
        for (String id : List.of(system, blocked, historical.getId())) {
            assertThat(outbox(id)).isNull();
            assertThat(push.eligible(id, 20L)).isFalse();
        }
        worker.dispatch();
        verify(producer, never()).send(any());
        assertThat(db.indexOps(ChatMessage.class).getIndexInfo()).anyMatch(index ->
                "chat_push_due_idx".equals(index.getName()));
    }

    private String send(MessageType type, String content) {
        return chat.processMessage(new ChatMessageRequest(roomId, 10L, "sender", content, type)).id();
    }

    private ChatMessage.PushOutbox outbox(String id) {
        return messages.findById(id).orElseThrow().getPushOutbox();
    }

    private void makeDue(String id) {
        db.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("pushOutbox.availableAt", Instant.now().minusSeconds(1)), ChatMessage.class);
    }
}
