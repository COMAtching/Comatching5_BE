package com.comatching.matching.domain.outbox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.comatching.common.dto.event.matching.MatchingSuccessEvent;
import com.comatching.matching.domain.entity.MatchingHistory;
import com.comatching.matching.domain.repository.history.MatchingHistoryRepository;
import com.comatching.matching.infra.kafka.MatchingEventProducer;
import com.comatching.matching.support.MySqlContainerSupport;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 실제 MySQL의 커밋/잠금과 실제 Kafka 발행을 검증한다. 스케줄러는 직접 호출한다. */
@DataJpaTest(properties = {
    "spring.jpa.show-sql=false",
    "matching.outbox.enabled=false",
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
    "spring.kafka.producer.value-serializer=com.comatching.common.config.kafka.EventValueSerializer",
    "spring.kafka.producer.acks=all",
    "spring.kafka.producer.properties[enable.idempotence]=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MatchingOutboxIT.Config.class)
@Import(MatchingOutboxService.class)
@ImportAutoConfiguration(KafkaAutoConfiguration.class)
@EmbeddedKafka(partitions = 3, topics = MatchingOutboxIT.TOPIC)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("integration")
@DisplayName("매칭 Outbox 트랜잭션·발행·장애 복구")
class MatchingOutboxIT extends MySqlContainerSupport {

    static final String TOPIC = "matching-success-topic";

    @Autowired MatchingOutboxEventRepository outboxRepository;
    @Autowired MatchingHistoryRepository historyRepository;
    @Autowired MatchingOutboxService outboxService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired EmbeddedKafkaBroker broker;

    TransactionTemplate transaction;

    @BeforeEach
    void cleanDatabase() {
        transaction = new TransactionTemplate(transactionManager);
        outboxRepository.deleteAllInBatch();
        historyRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("기존 트랜잭션 없이 Outbox만 저장하는 호출을 거부한다")
    void requiresExistingTransaction() {
        assertThatThrownBy(() -> outboxService.enqueue(new MatchingSuccessEvent(1L, 10L, 20L)))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    @DisplayName("매칭 이력과 이벤트를 같은 트랜잭션에서 커밋한다")
    void commitsHistoryAndOutboxTogether() throws Exception {
        MatchingOutboxEvent outbox = saveMatching();
        assertThat(historyRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
        assertThat(outbox.getStatus()).isEqualTo(MatchingOutboxStatus.PENDING);
        assertThat(objectMapper.readValue(outbox.getPayload(), MatchingSuccessEvent.class))
            .isEqualTo(new MatchingSuccessEvent(outbox.getMatchingId(), 10L, 20L));
    }

    @Test
    @DisplayName("Outbox 저장 후 요청이 실패하면 매칭 이력과 Outbox 모두 롤백된다")
    void rollsBackBothOnRequestFailure() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            saveHistoryAndEnqueue();
            outboxRepository.flush();
            throw new IllegalStateException("request failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("request failed");
        assertThat(historyRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    @DisplayName("Outbox 제약 위반으로 저장에 실패해도 매칭 이력이 남지 않는다")
    void rollsBackHistoryWhenOutboxInsertFails() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            MatchingSuccessEvent event = saveHistoryAndEnqueue();
            outboxService.enqueue(event);
            outboxRepository.flush();
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(historyRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    @DisplayName("같은 이벤트를 동시에 선점해도 하나의 트랜잭션만 성공한다")
    void onlyOneWorkerClaimsTheSameRow() throws Exception {
        Long id = saveMatching().getId();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return claim(id, "worker-a");
            });
            var second = executor.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return claim(id, "worker-b");
            });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(row(id).getStatus()).isEqualTo(MatchingOutboxStatus.PROCESSING);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("만료 후 새 작업자가 선점하면 이전 토큰으로 완료·실패를 기록할 수 없다")
    void expiredOwnerCannotOverwriteNewOwner() {
        Long id = saveMatching().getId();
        assertThat(claim(id, "old")).isEqualTo(1);
        expire(id);
        assertThat(outboxRepository.findReadyIds(LocalDateTime.now(), 10)).contains(id);
        assertThat(claim(id, "new")).isEqualTo(1);
        assertThat(transaction.<Integer>execute(status ->
            outboxRepository.markPublished(id, "old", LocalDateTime.now()))).isZero();
        assertThat(transaction.<Integer>execute(status ->
            outboxRepository.markRetry(id, "old", LocalDateTime.now(), "old failure"))).isZero();
        assertThat(row(id).getProcessingToken()).isEqualTo("new");
        assertThat(transaction.<Integer>execute(status ->
            outboxRepository.markPublished(id, "new", LocalDateTime.now()))).isEqualTo(1);
        assertThat(outboxRepository.findReadyIds(LocalDateTime.now(), 10)).doesNotContain(id);
    }

    @Test
    @DisplayName("실제 Kafka에 matchingId key와 JSON을 발행한 뒤 PUBLISHED로 기록한다")
    void publishesJsonToKafka() throws Exception {
        MatchingOutboxEvent outbox = saveMatching();
        dispatcher(new MatchingEventProducer(kafkaTemplate)).dispatch();

        MatchingOutboxEvent published = row(outbox.getId());
        assertThat(published.getStatus()).isEqualTo(MatchingOutboxStatus.PUBLISHED);
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getProcessingToken()).isNull();
        try (Consumer<String, String> consumer = consumer()) {
            consumer.subscribe(java.util.List.of(TOPIC));
            ConsumerRecord<String, String> record = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (record == null && System.nanoTime() < deadline) {
                for (var candidate : consumer.poll(Duration.ofMillis(300))) {
                    if (outbox.getMatchingId().toString().equals(candidate.key())) {
                        record = candidate;
                        break;
                    }
                }
            }
            assertThat(record).isNotNull();
            assertThat(objectMapper.readTree(record.value()).isObject()).isTrue();
            assertThat(objectMapper.readValue(record.value(), MatchingSuccessEvent.class))
                .isEqualTo(new MatchingSuccessEvent(outbox.getMatchingId(), 10L, 20L));
        }
    }

    @Test
    @DisplayName("선점은 커밋하고 DB 트랜잭션 밖에서 ACK를 기다리며 조기 완료하지 않는다")
    void waitsForAckWithoutKeepingDatabaseTransactionOpen() throws Exception {
        Long id = saveMatching().getId();
        var acknowledgement = new CompletableFuture<SendResult<String, Object>>();
        var sendStarted = new CountDownLatch(1);
        MatchingEventProducer producer = mock(MatchingEventProducer.class);
        when(producer.sendMatchingSuccess(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sendStarted.countDown();
            return acknowledgement;
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var dispatch = executor.submit(() -> dispatcher(producer).dispatch());
            assertThat(sendStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(row(id).getStatus()).isEqualTo(MatchingOutboxStatus.PROCESSING);
            assertThat(row(id).getPublishedAt()).isNull();
            acknowledgement.complete(null);
            dispatch.get(5, TimeUnit.SECONDS);
            assertThat(row(id).getStatus()).isEqualTo(MatchingOutboxStatus.PUBLISHED);
        } finally {
            acknowledgement.completeExceptionally(new IllegalStateException("test cleanup"));
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("비동기 발행 실패는 재시도 예약으로 남고 다음 성공 시 완료된다")
    void retriesAfterAsynchronousFailure() {
        Long id = saveMatching().getId();
        MatchingEventProducer producer = mock(MatchingEventProducer.class);
        when(producer.sendMatchingSuccess(any()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")))
            .thenReturn(CompletableFuture.completedFuture(null));
        MatchingOutboxDispatcher dispatcher = dispatcher(producer);
        dispatcher.dispatch();
        MatchingOutboxEvent pending = row(id);
        assertThat(pending.getStatus()).isEqualTo(MatchingOutboxStatus.PENDING);
        assertThat(pending.getRetryCount()).isEqualTo(1);
        assertThat(pending.getLastError()).contains("broker unavailable");
        assertThat(pending.getProcessingToken()).isNull();
        assertThat(pending.getPublishedAt()).isNull();
        assertThat(outboxRepository.findReadyIds(pending.getNextAttemptAt().minusNanos(1000), 10))
            .doesNotContain(id);
        assertThat(outboxRepository.findReadyIds(pending.getNextAttemptAt(), 10)).contains(id);

        jdbc.update("UPDATE matching_outbox_events SET next_attempt_at = ? WHERE id = ?",
            LocalDateTime.now().minusSeconds(1), id);
        dispatcher.dispatch();
        assertThat(row(id).getStatus()).isEqualTo(MatchingOutboxStatus.PUBLISHED);
        assertThat(row(id).getRetryCount()).isEqualTo(1);
        verify(producer, times(2)).sendMatchingSuccess(any());
    }

    @Test
    @DisplayName("10초 응답 시간 초과 후 늦은 성공이 와도 Outbox를 잘못 완료하지 않는다")
    void timeoutStaysRetryableDespiteLateAcknowledgement() {
        Long id = saveMatching().getId();
        var acknowledgement = new CompletableFuture<SendResult<String, Object>>();
        MatchingEventProducer producer = mock(MatchingEventProducer.class);
        when(producer.sendMatchingSuccess(any())).thenReturn(acknowledgement);
        dispatcher(producer).dispatch();
        assertThat(row(id).getStatus()).isEqualTo(MatchingOutboxStatus.PENDING);
        assertThat(row(id).getLastError()).contains("TimeoutException");
        acknowledgement.complete(null);
        assertThat(row(id).getStatus()).isEqualTo(MatchingOutboxStatus.PENDING);
        assertThat(row(id).getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("선점 후 서버가 사라진 작업도 만료 뒤 발행기가 복구한다")
    void recoversAbandonedClaim() {
        Long id = saveMatching().getId();
        claim(id, "terminated-worker");
        expire(id);
        MatchingEventProducer producer = mock(MatchingEventProducer.class);
        when(producer.sendMatchingSuccess(any())).thenReturn(CompletableFuture.completedFuture(null));
        dispatcher(producer).dispatch();
        assertThat(row(id).getStatus()).isEqualTo(MatchingOutboxStatus.PUBLISHED);
        assertThat(row(id).getProcessingToken()).isNull();
        verify(producer).sendMatchingSuccess(any());
    }

    @Test
    @DisplayName("Kafka 성공 후 완료 상태 저장이 실패해도 만료 뒤 재발행할 수 있다")
    void retriesWhenPublicationSucceededButDatabaseCompletionFailed() {
        Long id = saveMatching().getId();
        MatchingEventProducer producer = mock(MatchingEventProducer.class);
        when(producer.sendMatchingSuccess(any())).thenReturn(CompletableFuture.completedFuture(null));
        MatchingOutboxEventRepository failingRepository = mock(
            MatchingOutboxEventRepository.class, delegatesTo(outboxRepository));
        doThrow(new DataAccessResourceFailureException("database unavailable after acknowledgement"))
            .when(failingRepository).markPublished(eq(id), anyString(), any());
        new MatchingOutboxDispatcher(failingRepository, producer, objectMapper, transactionManager).dispatch();

        assertThat(row(id).getStatus()).isEqualTo(MatchingOutboxStatus.PROCESSING);
        assertThat(row(id).getPublishedAt()).isNull();
        expire(id);
        dispatcher(producer).dispatch();
        assertThat(row(id).getStatus()).isEqualTo(MatchingOutboxStatus.PUBLISHED);
        // 이 중복 발행 가능성 때문에 Consumer의 matchingId 멱등성이 필요하다.
        verify(producer, times(2)).sendMatchingSuccess(any());
    }

    private MatchingOutboxEvent saveMatching() {
        transaction.executeWithoutResult(status -> saveHistoryAndEnqueue());
        return outboxRepository.findAll().get(0);
    }

    private MatchingSuccessEvent saveHistoryAndEnqueue() {
        MatchingHistory history = historyRepository.save(MatchingHistory.builder()
            .memberId(10L).partnerId(20L).build());
        MatchingSuccessEvent event = new MatchingSuccessEvent(history.getId(), 10L, 20L);
        outboxService.enqueue(event);
        return event;
    }

    private MatchingOutboxEvent row(Long id) {
        return outboxRepository.findById(id).orElseThrow();
    }

    private int claim(Long id, String token) {
        return transaction.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            return outboxRepository.claim(id, token, now, now.plusSeconds(120));
        });
    }

    private void expire(Long id) {
        jdbc.update("UPDATE matching_outbox_events SET processing_until = ? WHERE id = ?",
            LocalDateTime.now().minusSeconds(1), id);
    }

    private MatchingOutboxDispatcher dispatcher(MatchingEventProducer producer) {
        return new MatchingOutboxDispatcher(outboxRepository, producer, objectMapper, transactionManager);
    }

    private Consumer<String, String> consumer() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "matching-outbox-probe-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(properties,
            new StringDeserializer(), new StringDeserializer()).createConsumer();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {MatchingHistory.class, MatchingOutboxEvent.class})
    @EnableJpaRepositories(basePackageClasses = {MatchingHistoryRepository.class, MatchingOutboxEventRepository.class})
    static class Config {
        @Bean ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
