package com.comatching.notification.infra.kafka.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.comatching.common.config.kafka.KafkaDltAlertNotifier;
import com.comatching.common.config.kafka.KafkaDltRedriveService;
import com.comatching.common.config.kafka.KafkaEventRoute;
import com.comatching.common.config.kafka.KafkaEventRoute.ItemsGrantedGroups;
import com.comatching.common.config.kafka.KafkaEventRoute.ReplayTopics;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.event.order.ItemsGranted;
import com.comatching.common.dto.event.order.RefundCompleted;
import com.comatching.notification.NotificationApplication;
import com.comatching.notification.domain.delivery.DeliveryUncertainException;
import com.comatching.notification.domain.service.fcm.FCMService;
import com.comatching.notification.domain.service.fcm.FirebaseMessageSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.messaging.Message;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
@DirtiesContext
@SpringBootTest(classes = NotificationApplication.class, properties = {
        "internal.service-token=test-internal", "spring.mail.password=test",
        "fcm.credentials=classpath:missing-test-credentials.json", "fcm.service-account-b64=",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}", "comatching.kafka.dlt-alert-webhook-url="
})
@EmbeddedKafka(partitions = 1, topics = {ItemsGranted.TOPIC, ReplayTopics.ITEMS_GRANTED_MAIL, ReplayTopics.ITEMS_GRANTED_PUSH,
        RefundCompleted.TOPIC, ReplayTopics.REFUND_MAIL})
class ItemsGrantedDeliveryIT {
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("comatching_notification").withInitScript("05-create-notification-delivery.sql")
            .withCommand("--skip-log-bin");
    @Container static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private static HttpServer users;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) throws Exception {
        users = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        users.createContext("/api/internal/users/", exchange -> {
            try (exchange) {
                if (!"test-internal".equals(exchange.getRequestHeaders().getFirst("X-Internal-Token"))) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                String[] path = exchange.getRequestURI().getPath().split("/");
                if (path.length != 6 || !"notification-email".equals(path[5])) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                if ("127".equals(path[4])) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                byte[] address = ("member" + path[4] + "@example.invalid").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, address.length);
                exchange.getResponseBody().write(address);
            }
        });
        users.start();
        properties.add("user-service.url", () -> "http://127.0.0.1:" + users.getAddress().getPort());
        properties.add("chat-service.url", () -> "http://127.0.0.1:" + users.getAddress().getPort());
        properties.add("spring.datasource.url", mysql::getJdbcUrl);
        properties.add("spring.datasource.username", mysql::getUsername);
        properties.add("spring.datasource.password", mysql::getPassword);
        properties.add("spring.data.redis.host", redis::getHost);
        properties.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @AfterAll static void stopUsers() {
        if (users != null) users.stop(0);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaTemplate<String, Object> kafka;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired KafkaDltRedriveService redrive;
    @Autowired ItemsGrantedConsumer consumer;
    @Autowired FCMService fcm;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate pushRedis;
    @MockitoBean JavaMailSenderImpl smtp;
    @MockitoBean FirebaseMessageSender firebase;
    @MockitoBean KafkaDltAlertNotifier alerts;

    @Test
    void repeatedSourceAndChannelReplaysSendOncePerChannel() throws Exception {
        var event = event(123);
        publish(ItemsGranted.TOPIC, event);
        var duplicate = publish(ItemsGranted.TOPIC, event);
        awaitCommit(ItemsGrantedGroups.MAIL, duplicate);
        awaitCommit(ItemsGrantedGroups.PUSH, duplicate);
        awaitCommit(ItemsGrantedGroups.MAIL, publish(ReplayTopics.ITEMS_GRANTED_MAIL, event));
        awaitCommit(ItemsGrantedGroups.PUSH, publish(ReplayTopics.ITEMS_GRANTED_PUSH, event));

        assertThat(states(event)).containsExactly("SENT", "SENT");
        verify(smtp).send(argThat((SimpleMailMessage m) ->
                List.of(m.getTo()).equals(List.of("member123@example.invalid")) && m.getText().contains("매칭권 2장")));
        verify(firebase).send(any(Message.class));
        verifyNoMoreInteractions(smtp, firebase);
    }

    @Test
    void refundConfirmationAndReplaySendOneMailAndNoPush() throws Exception {
        var event = new RefundCompleted("refund-completed:131", 131, 131, 3500, LocalDateTime.now());
        for (String topic : List.of(RefundCompleted.TOPIC, RefundCompleted.TOPIC, ReplayTopics.REFUND_MAIL)) {
            var sent = kafka.send(topic, "131", mapper.writeValueAsBytes(event)).get(10, TimeUnit.SECONDS);
            awaitCommit(KafkaEventRoute.Groups.REFUND_MAIL, sent.getRecordMetadata());
        }
        assertThat(jdbc.queryForList("SELECT state FROM notification_delivery WHERE event_id=? AND handler_id='REFUND_MAIL'",
                String.class, event.eventId())).containsExactly("SENT");
        verify(smtp).send(argThat((SimpleMailMessage m) -> List.of(m.getTo()).equals(List.of("member131@example.invalid"))
                && m.getSubject().contains("환불") && m.getText().contains("131") && m.getText().contains("3500원")));
        verifyNoMoreInteractions(smtp);
        verifyNoInteractions(firebase);
    }

    @Test
    void mailRejectionGoesToMailDltAndOnlyMailIsRedriven() throws Exception {
        var event = event(124);
        doThrow(new MailAuthenticationException("test SMTP rejection")).when(smtp).send(any(SimpleMailMessage.class));
        var source = publish(ItemsGranted.TOPIC, event);
        awaitCommit(ItemsGrantedGroups.MAIL, source);
        awaitCommit(ItemsGrantedGroups.PUSH, source);
        assertThat(states(event)).containsExactly("RETRYABLE", "SENT");
        verify(alerts).notifyParked(argThat(r -> "124".equals(r.key())), any(), eq(KafkaEventRoute.ITEMS_GRANTED_MAIL));
        verify(smtp, times(4)).send(any(SimpleMailMessage.class)); // 최초 + 1·2·4초 후 재시도
        doNothing().when(smtp).send(any(SimpleMailMessage.class));

        var result = redrive.redrive(KafkaEventRoute.ITEMS_GRANTED_MAIL);
        assertThat(result.replayTopic()).isEqualTo(ReplayTopics.ITEMS_GRANTED_MAIL);
        assertThat(result.redriven()).isPositive();
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(states(event)).containsExactly("SENT", "SENT"));
        verify(smtp, times(5)).send(any(SimpleMailMessage.class));
        verify(firebase).send(any(Message.class));
        try (var admin = admin()) {
            var pushOffsets = admin.listConsumerGroupOffsets(ItemsGrantedGroups.PUSH)
                    .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            assertThat(pushOffsets).doesNotContainKey(new TopicPartition(ReplayTopics.ITEMS_GRANTED_MAIL, 0));
        }
    }

    @Test
    void ambiguousMailOutcomeIsParkedEvenWhenReplayed() throws Exception {
        var event = event(125);
        doThrow(new MailSendException("test lost acknowledgement")).when(smtp).send(any(SimpleMailMessage.class));
        var source = publish(ItemsGranted.TOPIC, event);
        awaitCommit(ItemsGrantedGroups.MAIL, source);
        awaitCommit(ItemsGrantedGroups.PUSH, source);
        awaitCommit(ItemsGrantedGroups.MAIL, publish(ReplayTopics.ITEMS_GRANTED_MAIL, event));
        assertThat(states(event)).containsExactly("UNKNOWN", "SENT");
        verify(smtp).send(any(SimpleMailMessage.class));
        verify(firebase).send(any(Message.class));
    }

    @Test
    void secondWorkerCannotSendWhileFirstWorkerIsInSmtp() throws Exception {
        var event = event(126);
        var sending = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            sending.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(smtp).send(any(SimpleMailMessage.class));
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> consumer.mail(event));
            assertThat(sending.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> consumer.mail(event)).isInstanceOf(DeliveryUncertainException.class);
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(states(event)).containsExactly("SENT");
        verify(smtp).send(any(SimpleMailMessage.class));
    }

    @Test
    void noMailRecipientAndNoPushTokenAreRecordedAsSkipped() throws Exception {
        var event = granted(127);
        var source = publish(ItemsGranted.TOPIC, event);
        awaitCommit(ItemsGrantedGroups.MAIL, source);
        awaitCommit(ItemsGrantedGroups.PUSH, source);
        awaitCommit(ItemsGrantedGroups.MAIL, publish(ReplayTopics.ITEMS_GRANTED_MAIL, event));
        awaitCommit(ItemsGrantedGroups.PUSH, publish(ReplayTopics.ITEMS_GRANTED_PUSH, event));
        assertThat(states(event)).containsExactly("SKIPPED", "SKIPPED");
        verifyNoInteractions(smtp, firebase);
    }

    @Test
    void ambiguousPushOutcomeIsNotResentOnReplay() throws Exception {
        var event = event(128);
        when(firebase.send(any())).thenThrow(new IllegalStateException("test connection lost during send"));
        var source = publish(ItemsGranted.TOPIC, event);
        awaitCommit(ItemsGrantedGroups.MAIL, source);
        awaitCommit(ItemsGrantedGroups.PUSH, source);
        awaitCommit(ItemsGrantedGroups.PUSH, publish(ReplayTopics.ITEMS_GRANTED_PUSH, event));
        assertThat(states(event)).containsExactly("SENT", "UNKNOWN");
        verify(smtp).send(any(SimpleMailMessage.class));
        verify(firebase).send(any(Message.class));
    }

    @Test
    void losingTheSentUpdateDoesNotSendAcceptedMailAgain() throws Exception {
        var event = event(129);
        jdbc.execute("""
                CREATE TRIGGER test_fail_sent BEFORE UPDATE ON notification_delivery FOR EACH ROW
                BEGIN
                    IF NEW.event_id='items-granted:129' AND NEW.handler_id='GRANT_MAIL' AND NEW.state='SENT' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='simulated ledger write failure';
                    END IF;
                END
                """);
        try {
            var source = publish(ItemsGranted.TOPIC, event);
            awaitCommit(ItemsGrantedGroups.MAIL, source);
            awaitCommit(ItemsGrantedGroups.PUSH, source);
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_sent");
        }
        awaitCommit(ItemsGrantedGroups.MAIL, publish(ReplayTopics.ITEMS_GRANTED_MAIL, event));
        assertThat(states(event)).containsExactly("SENDING", "SENT");
        verify(smtp).send(any(SimpleMailMessage.class));
        verify(firebase).send(any(Message.class));
    }

    private ItemsGranted event(long id) {
        com.comatching.notification.support.TestInstallations.register(pushRedis, fcm, id, "test-token-" + id);
        return granted(id);
    }

    private ItemsGranted granted(long id) {
        return new ItemsGranted("items-granted:" + id, id, id, LocalDateTime.now(),
                List.of(new ItemsGranted.GrantedItem(ItemType.MATCHING_TICKET, 2)));
    }

    private List<String> states(ItemsGranted event) {
        return jdbc.queryForList("SELECT state FROM notification_delivery WHERE event_id=? AND handler_id IN (\'GRANT_MAIL\',\'GRANT_PUSH\') ORDER BY handler_id",
                String.class, event.eventId());
    }

    private RecordMetadata publish(String topic, ItemsGranted event) throws Exception {
        return kafka.send(topic, Long.toString(event.orderId()), mapper.writeValueAsBytes(event))
                .get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()));
    }

    private void awaitCommit(String group, RecordMetadata record) {
        var partition = new TopicPartition(record.topic(), record.partition());
        try (var admin = admin()) {
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                assertThat(offsets).containsKey(partition);
                assertThat(offsets.get(partition).offset()).isGreaterThan(record.offset());
            });
        }
    }
}
