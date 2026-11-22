package com.comatching.matching.domain.outbox;

import com.comatching.common.dto.event.matching.MatchingSuccessEvent;
import com.comatching.matching.infra.kafka.MatchingEventProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "matching.outbox",
        name = "enabled",
        havingValue = "true"
)
public class MatchingOutboxDispatcher {

    private static final int BATCH_SIZE = 10;
    private static final long LEASE_SECONDS = 120;
    private static final long SEND_WAIT_SECONDS = 10;

    private final MatchingOutboxEventRepository outboxRepository;
    private final MatchingEventProducer producer;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public MatchingOutboxDispatcher(
            MatchingOutboxEventRepository outboxRepository,
            MatchingEventProducer producer,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.outboxRepository = outboxRepository;
        this.producer = producer;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate.setTimeout(10);
    }

    // 메서드 전체에 @Transactional을 붙이지 않는다.
    // Kafka 전송을 기다리는 동안 DB 트랜잭션을 열어 두지 않기 위해서다.
    @Scheduled(fixedDelayString = "${matching.outbox.poll-interval-ms:1000}")
    public void dispatch() {
        var ids = outboxRepository.findReadyIds(LocalDateTime.now(), BATCH_SIZE);

        for (Long id : ids) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                publishOne(id);
            } catch (InterruptedException e) {
                // 종료 신호를 보존한다. 미완료 작업은 선점 만료 후 재처리된다.
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // 상태 저장에 실패해도 PROCESSING 만료 후 다시 처리할 수 있다.
                log.error("Matching outbox processing failed. outboxId={}", id, e);
            }
        }
    }

    private void publishOne(Long id) throws InterruptedException {
        String token = UUID.randomUUID().toString();

        // 1. 짧은 트랜잭션으로 한 건을 선점하고 COMMIT한다.
        MatchingOutboxEvent outbox = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            int claimed = outboxRepository.claim(
                    id, token, now, now.plusSeconds(LEASE_SECONDS)
            );

            if (claimed == 0) {
                return null;
            }
            return outboxRepository.findById(id).orElseThrow();
        });

        if (outbox == null) {
            return;
        }

        // 2. DB 트랜잭션 밖에서 Kafka의 성공 응답을 기다린다.
        try {
            MatchingSuccessEvent event = objectMapper.readValue(
                    outbox.getPayload(), MatchingSuccessEvent.class
            );
            producer.sendMatchingSuccess(event).get(SEND_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            scheduleRetry(outbox, token, e);
            return;
        }

        // 3. 성공한 경우에만 별도 트랜잭션으로 완료 처리한다.
        Integer updated = transactionTemplate.execute(status ->
                outboxRepository.markPublished(id, token, LocalDateTime.now())
        );

        if (Integer.valueOf(1).equals(updated)) {
            log.info("Matching outbox published. outboxId={} matchingId={}", id, outbox.getMatchingId());
        } else {
            log.warn("Matching outbox ownership changed before completion. outboxId={}", id);
        }
    }

    private void scheduleRetry(MatchingOutboxEvent outbox, String token, Exception error) {
        // 1, 2, 4, 8, 16, 32, 60초. 이후에는 60초 간격으로 계속 시도한다.
        long delaySeconds = Math.min(60L, 1L << Math.min(outbox.getRetryCount(), 6));
        String message = error.getClass().getSimpleName() + ": " + error.getMessage();
        String lastError = message.substring(0, Math.min(message.length(), 500));

        Integer updated = transactionTemplate.execute(status ->
                outboxRepository.markRetry(outbox.getId(), token, LocalDateTime.now().plusSeconds(delaySeconds), lastError)
        );

        if (Integer.valueOf(1).equals(updated)) {
            log.warn("Matching outbox retry scheduled. outboxId={} delaySeconds={}",
                    outbox.getId(), delaySeconds, error);
        } else {
            log.warn("Matching outbox ownership changed before retry. outboxId={}",
                    outbox.getId());
        }
    }
}
