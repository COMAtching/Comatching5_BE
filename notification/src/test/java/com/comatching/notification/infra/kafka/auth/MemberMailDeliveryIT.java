package com.comatching.notification.infra.kafka.auth;

import static com.comatching.common.config.kafka.KafkaEventRoute.SIGNUP_MAIL;
import static com.comatching.common.config.kafka.KafkaEventRoute.WITHDRAW_MAIL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import com.comatching.common.config.kafka.KafkaConsumerConfig;
import com.comatching.common.config.kafka.KafkaDltAlertNotifier;
import com.comatching.common.config.kafka.KafkaDltRedriveService;
import com.comatching.common.config.kafka.KafkaDltTopicConfig;
import com.comatching.common.config.kafka.KafkaEventRoute;
import com.comatching.common.dto.event.member.MemberAuthEvent;
import com.comatching.common.dto.event.member.MemberWithdrawnEvent;
import com.comatching.notification.domain.delivery.NotificationDeliveryRepository;
import com.comatching.notification.domain.delivery.NotificationDeliveryService;
import com.comatching.notification.domain.service.email.EmailServiceImpl;
import com.comatching.notification.global.config.FcmKafkaErrorConfig;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.TopicPartition;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
@DirtiesContext
@SpringBootTest(classes = MemberMailDeliveryIT.Config.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "comatching.kafka.dlt-routes=SIGNUP_MAIL,WITHDRAW_MAIL"})
@EmbeddedKafka(partitions = 1, topics = {"member-signup", "member-withdraw"})
class MemberMailDeliveryIT {
    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    @Import({NotificationConsumer.class, EmailServiceImpl.class, NotificationDeliveryService.class,
            NotificationDeliveryRepository.class, KafkaConsumerConfig.class, KafkaDltTopicConfig.class, FcmKafkaErrorConfig.class})
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
            KafkaAutoConfiguration.class, ThymeleafAutoConfiguration.class})
    static class Config {}

    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("comatching_notification").withInitScript("05-create-notification-delivery.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", mysql::getJdbcUrl);
        properties.add("spring.datasource.username", mysql::getUsername);
        properties.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired KafkaTemplate<String, Object> kafka;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired KafkaDltRedriveService redrive;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSender smtp;
    @MockitoBean KafkaDltAlertNotifier alerts;

    @BeforeEach
    void prepareMimeMessages() {
        when(smtp.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void legacyAndNewEventsSendOneWelcomeAndOneGoodbyeForSameMember() throws Exception {
        var signup = new MemberAuthEvent(42L, "welcome@example.invalid", "코매칭회원", MemberAuthEvent.EventType.SIGNUP);
        var withdrawal = new MemberWithdrawnEvent(42L, "goodbye@example.invalid", LocalDateTime.of(2026, 11, 1, 9, 0));
        String oldSignup = """
                {"memberId":42,"email":"welcome@example.invalid","nickname":"코매칭회원","type":"SIGNUP"}
                """;
        String oldWithdrawal = """
                {"memberId":42,"email":"goodbye@example.invalid","withdrawnAt":"2026-11-01T09:00:00"}
                """;
        publishAndAwait(SIGNUP_MAIL, SIGNUP_MAIL.sourceTopic(), 42, oldSignup);
        publishAndAwait(SIGNUP_MAIL, SIGNUP_MAIL.sourceTopic(), 42, signup);
        publishAndAwait(SIGNUP_MAIL, SIGNUP_MAIL.replayTopic(), 42, signup);
        publishAndAwait(WITHDRAW_MAIL, WITHDRAW_MAIL.sourceTopic(), 42, withdrawal);
        publishAndAwait(WITHDRAW_MAIL, WITHDRAW_MAIL.replayTopic(), 42, oldWithdrawal);

        assertThat(state("member-signup:42", SIGNUP_MAIL)).isEqualTo("SENT");
        assertThat(state("member-withdraw:42", WITHDRAW_MAIL)).isEqualTo("SENT");
        var sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(smtp, times(2)).send(sent.capture());
        var welcome = sent.getAllValues().get(0);
        var goodbye = sent.getAllValues().get(1);
        assertThat(welcome.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo(signup.email());
        assertThat(welcome.getContent().toString()).contains(signup.nickname());
        assertThat(goodbye.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo(withdrawal.email());
        assertThat(goodbye.getContent().toString()).contains("이용해 주셔서 진심으로 감사드립니다");
    }

    @Test
    void mimePreparationFailureCanBeRedrivenAfterRepair() throws Exception {
        var signup = new MemberAuthEvent(43L, "repair@example.invalid", "회원", MemberAuthEvent.EventType.SIGNUP);
        var brokenMessage = mock(MimeMessage.class);
        doThrow(new MessagingException("test invalid MIME header")).when(brokenMessage).setSubject(anyString(), anyString());
        when(smtp.createMimeMessage()).thenReturn(brokenMessage);
        publishAndAwait(SIGNUP_MAIL, SIGNUP_MAIL.sourceTopic(), 43, signup);
        assertThat(state(signup.eventId(), SIGNUP_MAIL)).isEqualTo("RETRYABLE");
        verify(smtp, never()).send(any(MimeMessage.class));
        verify(alerts).notifyParked(argThat(r -> "43".equals(r.key())), any(), eq(SIGNUP_MAIL));

        prepareMimeMessages();
        assertThat(redrive.redrive(SIGNUP_MAIL).redriven()).isOne();
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(state(signup.eventId(), SIGNUP_MAIL)).isEqualTo("SENT"));
        publishAndAwait(SIGNUP_MAIL, SIGNUP_MAIL.replayTopic(), 43, signup);
        verify(smtp).send(any(MimeMessage.class));
    }

    @Test
    void uncertainWithdrawalMailIsParkedAgainWithoutResending() throws Exception {
        var event = new MemberWithdrawnEvent(44L, "uncertain@example.invalid", LocalDateTime.now());
        var lostReply = new SMTPSendFailedException(".", -1, "[EOF]", null, null,
                new Address[]{new InternetAddress(event.email())}, null);
        doAnswer(call -> {
            throw new MailSendException(Map.of(call.getArgument(0), lostReply));
        }).when(smtp).send(any(MimeMessage.class));
        publishAndAwait(WITHDRAW_MAIL, WITHDRAW_MAIL.sourceTopic(), 44, event);
        assertThat(state(event.eventId(), WITHDRAW_MAIL)).isEqualTo("UNKNOWN");
        assertThat(redrive.redrive(WITHDRAW_MAIL).redriven()).isOne();
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                verify(alerts, times(2)).notifyParked(argThat(r -> "44".equals(r.key())), any(), eq(WITHDRAW_MAIL)));
        assertThat(state(event.eventId(), WITHDRAW_MAIL)).isEqualTo("UNKNOWN");
        verify(smtp).send(any(MimeMessage.class));
    }

    private String state(String eventId, KafkaEventRoute route) {
        return jdbc.queryForObject("SELECT state FROM notification_delivery WHERE event_id=? AND handler_id=?",
                String.class, eventId, route.name());
    }

    private void publishAndAwait(KafkaEventRoute route, String topic, long memberId, Object payload) throws Exception {
        var record = kafka.send(topic, Long.toString(memberId), payload).get(10, TimeUnit.SECONDS).getRecordMetadata();
        var partition = new TopicPartition(topic, record.partition());
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var offsets = admin.listConsumerGroupOffsets(route.groupId()).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                assertThat(offsets).containsKey(partition);
                assertThat(offsets.get(partition).offset()).isGreaterThan(record.offset());
            });
        }
    }
}
