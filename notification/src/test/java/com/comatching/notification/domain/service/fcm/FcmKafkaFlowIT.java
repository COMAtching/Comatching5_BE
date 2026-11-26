package com.comatching.notification.domain.service.fcm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.ByteBuffer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.comatching.notification.support.TestInstallations;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.comatching.common.config.kafka.KafkaConsumerConfig;
import com.comatching.common.config.kafka.KafkaEventRoute;
import com.comatching.common.config.kafka.KafkaDltAlertNotifier;
import com.comatching.common.dto.event.chat.ChatMessageEvent;
import com.comatching.notification.global.config.FcmKafkaErrorConfig;
import com.comatching.notification.infra.kafka.chat.ChatEventConsumer;
import com.comatching.notification.domain.delivery.NotificationDeliveryRepository;
import com.comatching.notification.domain.delivery.NotificationDeliveryService;
import com.comatching.notification.infra.client.ChatPushEligibilityClient;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.IncomingHttpResponse;
import com.google.firebase.ErrorCode;
import com.google.firebase.FirebaseException;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boot의 실제 팩토리·오류 처리기·FCM 리스너를 연결한다.
 * Kafka·Redis·MySQL을 연결하고 chat 내부 API와 외부 Firebase·웹훅 응답을 제어한다.
 * 재시도 대기 시간을 줄이지 않아 실제 10·20·40초 정책의 연결까지 확인한다.
 */
@SpringBootTest(classes = FcmKafkaFlowIT.Config.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
    "spring.kafka.producer.value-serializer=com.comatching.common.config.kafka.EventValueSerializer",
    "spring.kafka.producer.acks=all",
    "spring.kafka.producer.properties[enable.idempotence]=true",
    "spring.kafka.consumer.group-id=notification-group",
    "spring.kafka.consumer.auto-offset-reset=earliest",
    "spring.kafka.consumer.enable-auto-commit=false",
    "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
    "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
    "spring.kafka.listener.ack-mode=record",
    "spring.kafka.listener.concurrency=1",
    "internal.service-token=test-chat-token",
    "comatching.kafka.dlt-alert-webhook-url="
})
@EmbeddedKafka(partitions = 1, topics = {FcmKafkaFlowIT.TOPIC, FcmKafkaFlowIT.DLT, FcmKafkaFlowIT.REPLAY})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class FcmKafkaFlowIT {
    static final String TOPIC = "chat-message-sent";
    static final String DLT = TOPIC + ".notification-group.DLT";
    static final String REPLAY = KafkaEventRoute.ReplayTopics.CHAT_NOTIFICATION;
    static final TopicPartition SOURCE_PARTITION = new TopicPartition(TOPIC, 0);
    static final TopicPartition DLT_PARTITION = new TopicPartition(DLT, 0);
    static final AtomicLong MEMBER_IDS = new AtomicLong(1_000);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("comatching_notification").withInitScript("05-create-notification-delivery.sql");
    private static final Map<String, String> eligibility = new ConcurrentHashMap<>();
    private static HttpServer chat;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        chat = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        chat.createContext("/api/internal/chat/messages/", exchange -> {
            try (exchange) {
                var path = exchange.getRequestURI().getPath().split("/");
                if (path.length != 7 || !"push-eligible".equals(path[6])
                        || !exchange.getRequestURI().getQuery().matches("targetUserId=[0-9]+")
                        || !"test-chat-token".equals(exchange.getRequestHeaders().getFirst("X-Internal-Token"))) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                String answer = eligibility.getOrDefault(path[5], "true");
                if ("unavailable".equals(answer)) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                byte[] body = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        chat.start();
        registry.add("chat-service.url", () -> "http://127.0.0.1:" + chat.getAddress().getPort());
    }

    @AfterAll static void stopChat() { if (chat != null) chat.stop(0); }

    @Autowired JdbcTemplate jdbc;
    @Autowired FCMService service;
    @Autowired StringRedisTemplate redis;
    @Autowired EmbeddedKafkaBroker broker;
    @MockitoBean FirebaseMessageSender sender;
    @MockitoBean KafkaDltAlertNotifier notifier;
    // Boot의 @Bean 반환 타입은 KafkaTemplate<?, ?>이므로 spy도 같은 타입으로 찾는다.
    @MockitoSpyBean(name = "kafkaTemplate") KafkaTemplate<?, ?> autoConfiguredTemplate;
    @Autowired KafkaTemplate<Object, Object> template;

    @Test
    @DisplayName("실제 Redis에서 UNREGISTERED 토큰을 삭제하고 DLT 없이 offset을 커밋한다")
    void unregisteredTokenIsDeletedAndCommitted() throws Exception {
        ChatMessageEvent event = event();
        register(event.targetUserId(), "expired-token");
        assertThat(redis.getExpire(tokenKey(event), TimeUnit.SECONDS)).isBetween(5_183_900L, 5_184_000L);
        FirebaseMessagingException unregistered = failure(MessagingErrorCode.UNREGISTERED);
        when(sender.send(any(Message.class))).thenThrow(unregistered);
        long beforeDlt = dltEndOffset();

        RecordMetadata sent = publish(event);
        awaitCommit(sent, Duration.ofSeconds(30));

        assertThat(redis.opsForHash().get(tokenKey(event), "token")).isNull();
        verify(sender, times(1)).send(any(Message.class));
        assertThat(dltEndOffset()).isEqualTo(beforeDlt);
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("전송 중 새로 등록된 토큰을 Lua 조건부 삭제가 보존한다")
    void tokenRegisteredDuringSendSurvivesCleanup() throws Exception {
        ChatMessageEvent event = event();
        register(event.targetUserId(), "old-token");
        FirebaseMessagingException unregistered = failure(MessagingErrorCode.UNREGISTERED);
        when(sender.send(any(Message.class))).thenAnswer(invocation -> {
            // FCMService가 old-token을 읽은 뒤, 실패 응답 전에 재등록되는 경쟁 상황.
            register(event.targetUserId(), "new-token");
            throw unregistered;
        });
        long beforeDlt = dltEndOffset();

        awaitCommit(publish(event), Duration.ofSeconds(30));

        assertThat(redis.opsForHash().get(tokenKey(event), "token")).isEqualTo("new-token-" + event.targetUserId());
        verify(sender, times(1)).send(any(Message.class));
        assertThat(dltEndOffset()).isEqualTo(beforeDlt);
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("토큰이 없으면 외부 호출·DLT 없이 처리 완료한다")
    void missingTokenSkipsAndCommits() throws Exception {
        long beforeDlt = dltEndOffset();
        awaitCommit(publish(event()), Duration.ofSeconds(30));
        verifyNoInteractions(sender, notifier);
        assertThat(dltEndOffset()).isEqualTo(beforeDlt);
    }

    @ParameterizedTest
    @ValueSource(strings = {TOPIC, REPLAY})
    @DisplayName("INVALID_ARGUMENT는 토큰 보존·최초 1회 호출·DLT 발행 후 커밋한다")
    void invalidArgumentParksWithoutRetry(String topic) throws Exception {
        ChatMessageEvent event = event();
        register(event.targetUserId(), "valid-token");
        FirebaseMessagingException invalidArgument = failure(MessagingErrorCode.INVALID_ARGUMENT);
        when(sender.send(any(Message.class))).thenThrow(invalidArgument);

        RecordMetadata sent = publish(topic, event);
        awaitCommit(sent, Duration.ofSeconds(30));

        assertDlt(event, sent);
        assertThat(redis.opsForHash().get(tokenKey(event), "token")).isEqualTo("valid-token-" + event.targetUserId());
        verify(sender, times(1)).send(any(Message.class));
        verify(notifier, times(1)).notifyParked(any(), any(), org.mockito.ArgumentMatchers.eq(KafkaEventRoute.CHAT_NOTIFICATION));
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    @DisplayName("429 또는 Retry-After가 있으면 10초 재시도 대신 바로 DLT에 보관한다")
    void serverRequestedDelayParksForManualRedrive(int status) throws Exception {
        ChatMessageEvent event = event();
        register(event.targetUserId(), "valid-token");
        IncomingHttpResponse response = mock(IncomingHttpResponse.class);
        when(response.getStatusCode()).thenReturn(status);
        when(response.getHeaders()).thenReturn(Map.of("Retry-After", List.of("120")));
        FirebaseMessagingException failure = failure(status == 429
            ? MessagingErrorCode.QUOTA_EXCEEDED : MessagingErrorCode.UNAVAILABLE, response);
        when(sender.send(any(Message.class))).thenThrow(failure);

        RecordMetadata sent = publish(event);
        awaitCommit(sent, Duration.ofSeconds(30));

        assertDlt(event, sent);
        verify(sender, times(1)).send(any(Message.class));
        assertThat(redis.opsForHash().get(tokenKey(event), "token")).isEqualTo("valid-token-" + event.targetUserId());
    }

    @ParameterizedTest
    @ValueSource(strings = {TOPIC, REPLAY})
    @DisplayName("첫 일시 장애 뒤 실제 10초 이상 기다려 성공하면 DLT 없이 커밋한다")
    void transientFailureRecoversAfterRealBackoff(String topic) throws Exception {
        ChatMessageEvent event = event();
        register(event.targetUserId(), "valid-token");
        List<Long> attempts = new CopyOnWriteArrayList<>();
        FirebaseMessagingException unavailable = explicitUnavailable();
        when(sender.send(any(Message.class))).thenAnswer(invocation -> {
            attempts.add(System.nanoTime());
            if (attempts.size() == 1) throw unavailable;
            return "firebase-message-id";
        });
        long beforeDlt = dltEndOffset();
        RecordMetadata sent = publish(topic, event);

        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> attempts.size() == 1);
        assertThat(committedOffset(new TopicPartition(sent.topic(), sent.partition()))).isLessThanOrEqualTo(sent.offset());
        awaitCommit(sent, Duration.ofSeconds(30));

        assertThat(attempts).hasSize(2);
        assertMinimumWait(attempts, 1, 10_000L);
        assertThat(dltEndOffset()).isEqualTo(beforeDlt);
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("실제 10·20·40초 재시도 소진 뒤 원본 key·payload·위치를 DLT에 보존한다")
    void exhaustedRetriesParkAndCommit() throws Exception {
        ChatMessageEvent event = event();
        register(event.targetUserId(), "valid-token");
        List<Long> attempts = new CopyOnWriteArrayList<>();
        FirebaseMessagingException unavailable = explicitUnavailable();
        when(sender.send(any(Message.class))).thenAnswer(invocation -> {
            attempts.add(System.nanoTime());
            throw unavailable;
        });
        RecordMetadata sent = publish(event);
        awaitCommit(sent, Duration.ofSeconds(120));

        assertThat(attempts).hasSize(4);
        assertMinimumWait(attempts, 1, 10_000L);
        assertMinimumWait(attempts, 2, 20_000L);
        assertMinimumWait(attempts, 3, 40_000L);
        assertDlt(event, sent);
        verify(notifier, times(1)).notifyParked(any(), any(), org.mockito.ArgumentMatchers.eq(KafkaEventRoute.CHAT_NOTIFICATION));
    }

    @Test
    @DisplayName("DLT 발행 실패 시 원본 offset을 넘기지 않고 DLT 복구 성공 뒤 커밋한다")
    void dltPublicationFailureDoesNotCommitSource() throws Exception {
        ChatMessageEvent event = event();
        register(event.targetUserId(), "valid-token");
        FirebaseMessagingException invalidArgument = failure(MessagingErrorCode.INVALID_ARGUMENT);
        when(sender.send(any(Message.class))).thenThrow(invalidArgument);
        AtomicInteger dltAttempts = new AtomicInteger();
        CountDownLatch allowDlt = new CountDownLatch(1);
        doAnswer(invocation -> {
            ProducerRecord<Object, Object> outbound = invocation.getArgument(0);
            if (DLT.equals(outbound.topic())) {
                if (dltAttempts.incrementAndGet() == 1) {
                    return CompletableFuture.failedFuture(new IllegalStateException("injected DLT send failure"));
                }
                if (!allowDlt.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release DLT recovery");
                }
            }
            return invocation.callRealMethod();
        }).when(template).send(org.mockito.ArgumentMatchers.<ProducerRecord<Object, Object>>any());
        long beforeDlt = dltEndOffset();
        RecordMetadata sent = publish(event);
        try {
            // 첫 실패를 오류 처리기가 받은 뒤, 다음 DLT 시도를 잠시 보류한다.
            Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> dltAttempts.get() >= 2);
            assertThat(committedOffset()).isLessThanOrEqualTo(sent.offset());
            assertThat(dltEndOffset()).isEqualTo(beforeDlt);
            verifyNoInteractions(notifier);
        } finally {
            allowDlt.countDown();
        }

        awaitCommit(sent, Duration.ofSeconds(30));
        assertDlt(event, sent);
        assertThat(dltEndOffset()).isEqualTo(beforeDlt + 1);
        verify(notifier, times(1)).notifyParked(any(), any(), org.mockito.ArgumentMatchers.eq(KafkaEventRoute.CHAT_NOTIFICATION));
    }

    private RecordMetadata publish(ChatMessageEvent event) throws Exception {
        return publish(TOPIC, event);
    }

    @Test
    void sameMessageIsSentOnceAcrossSourceAndReplayButDistinctMessagesAreSent() throws Exception {
        var first = event();
        var second = new ChatMessageEvent(first.targetUserId(), first.senderNickname(), first.roomId(),
                first.content(), first.timestamp(), first.type(), first.messageId() + "-2");
        register(first.targetUserId(), "valid-token");
        awaitCommit(publish(first), Duration.ofSeconds(20));
        awaitCommit(publish(first), Duration.ofSeconds(20));
        awaitCommit(publish(REPLAY, first), Duration.ofSeconds(20));
        awaitCommit(publish(second), Duration.ofSeconds(20));
        assertThat(state(first)).isEqualTo("SENT");
        assertThat(state(second)).isEqualTo("SENT");
        verify(sender, times(2)).send(any(Message.class));
    }

    @Test
    void lostFcmAcknowledgementIsNotSentAgainOnReplay() throws Exception {
        var event = event();
        register(event.targetUserId(), "valid-token");
        when(sender.send(any(Message.class))).thenThrow(failure(MessagingErrorCode.UNAVAILABLE));
        awaitCommit(publish(event), Duration.ofSeconds(20));
        assertThat(state(event)).isEqualTo("UNKNOWN");
        awaitCommit(publish(REPLAY, event), Duration.ofSeconds(20));
        assertThat(state(event)).isEqualTo("UNKNOWN");
        verify(sender).send(any(Message.class));
        verify(notifier, times(2)).notifyParked(any(), any(), eq(KafkaEventRoute.CHAT_NOTIFICATION));
    }

    @Test
    void failedEligibilityLookupIsRetriedAndNewlyReadMessageIsSkipped() throws Exception {
        var event = event();
        register(event.targetUserId(), "valid-token");
        eligibility.put(event.messageId(), "unavailable");
        var record = publish(event);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(state(event)).isEqualTo("RETRYABLE"));
        eligibility.put(event.messageId(), "false");
        awaitCommit(record, Duration.ofSeconds(25));
        assertThat(state(event)).isEqualTo("SKIPPED");
        awaitCommit(publish(REPLAY, event), Duration.ofSeconds(20));
        verifyNoInteractions(sender, notifier);
    }

    @Test
    void legacyJsonIsParkedWithoutSendingOrClaimingADelivery() throws Exception {
        var event = event();
        com.fasterxml.jackson.databind.node.ObjectNode payload = new ObjectMapper().valueToTree(event);
        payload.remove("messageId");
        long before = dltEndOffset();
        var record = template.send(TOPIC, event.roomId(), payload.toString()).get(10, TimeUnit.SECONDS).getRecordMetadata();
        awaitCommit(record, Duration.ofSeconds(20));
        assertThat(dltEndOffset()).isEqualTo(before + 1);
        assertThat(jdbc.queryForList("SELECT event_id FROM notification_delivery WHERE event_id='chat-message:null'")).isEmpty();
        verifyNoInteractions(sender);
    }

    private String state(ChatMessageEvent event) {
        return jdbc.queryForList("SELECT state FROM notification_delivery WHERE event_id=? AND handler_id='CHAT_PUSH'",
                String.class, event.eventId()).stream().findFirst().orElse(null);
    }

    private RecordMetadata publish(String topic, ChatMessageEvent event) throws Exception {
        return template.send(topic, event.roomId(), event).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private void awaitCommit(RecordMetadata sent, Duration timeout) {
        try (AdminClient admin = adminClient()) {
            Awaitility.await().pollInterval(Duration.ofMillis(250)).atMost(timeout).untilAsserted(() ->
                assertThat(committedOffset(admin, new TopicPartition(sent.topic(), sent.partition()))).isEqualTo(sent.offset() + 1));
        }
    }

    private long committedOffset() throws Exception {
        return committedOffset(SOURCE_PARTITION);
    }

    private long committedOffset(TopicPartition partition) throws Exception {
        try (AdminClient admin = adminClient()) {
            return committedOffset(admin, partition);
        }
    }

    private AdminClient adminClient() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()));
    }

    private long committedOffset(AdminClient admin, TopicPartition partition) throws Exception {
        var offsets = admin.listConsumerGroupOffsets("notification-group")
            .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
        return offsets.containsKey(partition) ? offsets.get(partition).offset() : -1;
    }

    private long dltEndOffset() {
        try (Consumer<String, String> consumer = dltProbe()) {
            return consumer.endOffsets(List.of(DLT_PARTITION)).get(DLT_PARTITION);
        }
    }

    private Consumer<String, String> dltProbe() {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
            ConsumerConfig.GROUP_ID_CONFIG, "fcm-dlt-probe-" + UUID.randomUUID(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false), new StringDeserializer(), new StringDeserializer());
    }

    private void assertDlt(ChatMessageEvent event, RecordMetadata source) throws Exception {
        try (Consumer<String, String> consumer = dltProbe()) {
            consumer.assign(List.of(DLT_PARTITION));
            consumer.seekToBeginning(List.of(DLT_PARTITION));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    if (!event.roomId().equals(record.key())) continue;
                    assertThat(new ObjectMapper().readValue(record.value(), ChatMessageEvent.class)).isEqualTo(event);
                    assertThat(new String(record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                        StandardCharsets.UTF_8)).isEqualTo(source.topic());
                    assertThat(ByteBuffer.wrap(record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value())
                        .getLong()).isEqualTo(source.offset());
                    return;
                }
            }
            fail("DLT record not found: " + event.roomId());
        }
    }

    private void assertMinimumWait(List<Long> attempts, int index, long minimumMs) {
        assertThat(TimeUnit.NANOSECONDS.toMillis(attempts.get(index) - attempts.get(index - 1)))
            .as("retry %s minimum delay", index).isGreaterThanOrEqualTo(minimumMs);
    }

    private FirebaseMessagingException failure(MessagingErrorCode code) {
        return failure(code, null);
    }

    private FirebaseMessagingException explicitUnavailable() {
        var response = mock(IncomingHttpResponse.class);
        when(response.getStatusCode()).thenReturn(503);
        return failure(MessagingErrorCode.UNAVAILABLE, response);
    }

    private FirebaseMessagingException failure(MessagingErrorCode code, IncomingHttpResponse response) {
        // DLT는 cause의 실제 stack trace도 직렬화한다. 생성자를 건너뛴 Throwable mock 대신
        // SDK의 비공개 팩토리로 정상 초기화된 예외를 만들어 전송 대역에서 던진다.
        return ReflectionTestUtils.invokeMethod(FirebaseMessagingException.class, "withMessagingErrorCode",
            new FirebaseException(ErrorCode.INTERNAL, "simulated FCM failure: " + code, null, response), code);
    }

    private ChatMessageEvent event() {
        long id = MEMBER_IDS.incrementAndGet();
        return new ChatMessageEvent(id, "sender", "room-" + id, "hello", "2026-11-19T12:00:00", "CHAT", "message-" + id);
    }

    @Test
    void sharedBrowserMovesAtomicallyAndOtherInstallationsReceiveOnlyOneGenericPush() throws Exception {
        var a = event();
        var b = event();
        var store = new com.comatching.common.auth.PushInstallations(redis);
        var browser = UUID.randomUUID();
        var phone = UUID.randomUUID();
        var aBrowser = TestInstallations.activate(redis, browser, a.targetUserId(), UUID.randomUUID());
        var aPhone = TestInstallations.activate(redis, phone, a.targetUserId(), UUID.randomUUID());
        service.register(browser, aBrowser, "shared-" + browser);
        service.register(phone, aPhone, "phone-" + phone);
        var bBrowser = TestInstallations.activate(redis, browser, b.targetUserId(), UUID.randomUUID());
        assertThat(store.target(browser, a.targetUserId())).isNull();
        service.register(browser, bBrowser, "shared-" + browser);
        assertThatThrownBy(() -> service.register(browser, aBrowser, "shared-" + browser))
                .isInstanceOf(com.comatching.common.exception.BusinessException.class);
        assertThatThrownBy(() -> service.register(phone, aPhone, "shared-" + browser))
                .isInstanceOf(com.comatching.common.exception.BusinessException.class);
        store.revoke(a.targetUserId(), aBrowser.login().id());
        assertThat(store.target(browser, b.targetUserId()).token()).isEqualTo("shared-" + browser);
        var tablet = UUID.randomUUID();
        var aTablet = TestInstallations.activate(redis, tablet, a.targetUserId(), UUID.randomUUID());
        service.register(tablet, aTablet, "tablet-" + tablet);
        service.sendNotification(a);
        service.sendNotification(a);
        var sent = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(sender, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(message -> ReflectionTestUtils.getField(message, "token"))
                .containsExactlyInAnyOrder("phone-" + phone, "tablet-" + tablet);
        store.revoke(a.targetUserId(), null);
        assertThat(store.installations(a.targetUserId())).isEmpty();
        assertThat(store.target(browser, b.targetUserId())).isNotNull();
    }

    @Test
    void aPartiallySuccessfulEventRetriesOnlyTheUnsentInstallation() throws Exception {
        var event = event();
        var phone = UUID.randomUUID();
        var browser = UUID.randomUUID();
        service.register(phone, TestInstallations.activate(redis, phone, event.targetUserId(), UUID.randomUUID()), "phone-" + phone);
        service.register(browser, TestInstallations.activate(redis, browser, event.targetUserId(), UUID.randomUUID()), "browser-" + browser);
        var browserAttempts = new java.util.concurrent.atomic.AtomicInteger();
        var phoneAttempts = new java.util.concurrent.atomic.AtomicInteger();
        when(sender.send(any(Message.class))).thenAnswer(invocation -> {
            var token = ReflectionTestUtils.getField(invocation.<Message>getArgument(0), "token");
            if (("browser-" + browser).equals(token)) {
                if (browserAttempts.incrementAndGet() == 1) throw explicitUnavailable();
            } else {
                phoneAttempts.incrementAndGet();
            }
            return "accepted";
        });
        assertThatThrownBy(() -> service.sendNotification(event))
            .isInstanceOf(com.comatching.notification.domain.delivery.DeliveryNotSentException.class);
        service.sendNotification(event);
        service.sendNotification(event);
        assertThat(browserAttempts.get()).isEqualTo(2);
        assertThat(phoneAttempts.get()).isEqualTo(1);
    }

    private void register(long member, String token) {
        TestInstallations.register(redis, service, member, token + "-" + member);
    }

    private String tokenKey(ChatMessageEvent event) {
        return "push:v2:{installations}:i:" + com.comatching.notification.support.TestInstallations.device(event.targetUserId());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    @Import({KafkaConsumerConfig.class, FcmKafkaErrorConfig.class, FCMService.class, ChatEventConsumer.class,
            ChatPushEligibilityClient.class, NotificationDeliveryRepository.class, NotificationDeliveryService.class})
    @ImportAutoConfiguration({KafkaAutoConfiguration.class, RedissonAutoConfigurationV2.class, RedisAutoConfiguration.class,
            RestClientAutoConfiguration.class, DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class})
    static class Config { }
}
