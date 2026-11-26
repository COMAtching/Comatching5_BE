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
import com.comatching.chat.domain.service.block.BlockServiceImpl;
import com.comatching.chat.domain.service.chatroom.ChatRoomServiceImpl;
import com.comatching.chat.infra.client.MemberClient;
import com.comatching.chat.global.exception.ChatErrorCode;
import com.comatching.common.exception.BusinessException;
import org.springframework.data.domain.PageRequest;
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
    @MockitoBean com.comatching.common.operations.FeatureGate features;
    static final String TOPIC = "chat-message-sent";

    @Configuration
    @EntityScan(basePackageClasses = ChatMessage.class)
    @EnableMongoRepositories(basePackageClasses = ChatMessageRepository.class)
    @Import({MongoConfig.class, ChatServiceImpl.class, ChatPushOutboxDispatcher.class,
            ChatEventProducer.class, InternalChatPushController.class, BlockServiceImpl.class, ChatRoomServiceImpl.class})
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
    @Autowired BlockService blockService;
    @Autowired ChatRoomServiceImpl roomService;
    @MockitoBean MemberClient members;
    @MockitoBean S3Service s3;
    private String roomId;

    @BeforeEach
    void prepareRoom() {
        var feature = com.comatching.common.operations.Feature.CHAT;
        when(features.state(feature)).thenReturn(new com.comatching.common.operations.FeatureState(feature, true, "", 1));
        messages.deleteAll();
        rooms.deleteAll();
        blocks.deleteAll();
        var room = ChatRoom.builder().matchingId(100L).initiatorUserId(10L).targetUserId(20L).build();
        room.updateLastReadAt(20L, LocalDateTime.now().minusMinutes(1));
        roomId = rooms.save(room).getId();
    }

    @Test
    void persistedImageSurvivesBrokerFailureAndLostAckAndKeepsItsKeyAndId() throws Exception {
        when(s3.attachChat(10L, roomId, "photo")).thenReturn("https://example.invalid/photo");
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
        blockService.blockUser(20L, 10L);
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

    @Test
    void blockerGetsAFreshViewWhileSenderKeepsTheSameRoomAndAllMessages() {
        String before = send(MessageType.TALK, "before");
        var page = PageRequest.of(0, 20);
        blockService.blockUser(20L, 10L);
        String during = send(MessageType.TALK, "during");
        assertThat(outbox(during)).isNull();
        assertThat(roomService.getMyChatRooms(20L)).isEmpty();
        assertThat(roomService.getTotalUnreadCount(20L)).isZero();
        assertThatThrownBy(() -> chat.getChatHistory(roomId, 20L, page))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ChatErrorCode.NOT_EXIST_CHATROOM);
        assertThatThrownBy(() -> chat.markAsRead(roomId, 20L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> roomService.validateRoomMember(roomId, 20L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> chat.processMessage(new ChatMessageRequest(roomId, 20L, "A", "hidden", MessageType.TALK)))
                .isInstanceOf(BusinessException.class);
        assertThat(roomService.getMyChatRooms(10L)).singleElement().satisfies(room ->
                assertThat(room.lastMessage()).isEqualTo("during"));
        assertThat(chat.getChatHistory(roomId, 10L, page)).extracting("id").containsExactly(before, during);

        blockService.unblockUser(20L, 10L);
        assertThat(blockService.getBlockedUsers(20L)).isEmpty();
        assertThat(push.eligible(before, 20L)).isFalse();
        assertThat(chat.getChatHistory(roomId, 20L, page)).isEmpty();
        assertThat(roomService.getMyChatRooms(20L)).singleElement().satisfies(room -> {
            assertThat(room.id()).isEqualTo(roomId);
            assertThat(room.lastMessage()).isNull();
            assertThat(room.lastMessageTime()).isNull();
            assertThat(room.unreadCount()).isZero();
        });
        assertThat(roomService.getTotalUnreadCount(20L)).isZero();

        String after = send(MessageType.TALK, "after");
        assertThat(outbox(after)).isNotNull();
        assertThat(push.eligible(after, 20L)).isTrue();
        assertThat(chat.getChatHistory(roomId, 20L, page)).extracting("id").containsExactly(after);
        assertThat(chat.getChatHistory(roomId, 20L, PageRequest.of(1, 1))).isEmpty();
        assertThat(chat.getChatHistory(roomId, 10L, page)).extracting("id").containsExactly(before, during, after);
        assertThat(roomService.getMyChatRooms(20L)).singleElement().satisfies(room -> {
            assertThat(room.lastMessage()).isEqualTo("after");
            assertThat(room.unreadCount()).isEqualTo(1);
        });

        blockService.blockUser(20L, 10L);
        assertThat(blocks.count()).isOne();
        blockService.unblockUser(20L, 10L);
        assertThat(chat.getChatHistory(roomId, 20L, page)).isEmpty();
        assertThat(chat.getChatHistory(roomId, 10L, page)).hasSize(3);
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
