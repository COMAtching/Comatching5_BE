package com.comatching.item.infra.bankdeposit;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "comatching.deposit-ingest.enabled", havingValue = "true")
public class DepositObservationPublisher {
    public static final String TOPIC = "kb-deposit-observed-v1";
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BankScanKafka completedScans;

    private final DepositObservationStore store;
    private final KafkaTemplate<String, Object> kafka;

    // A broker outage may block the producer for max.block.ms. Keep the shared
    // item-service scheduler available for order expiry and grant processing.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "deposit-publisher"));
    private final AtomicBoolean running = new AtomicBoolean();

    @PreDestroy
    void close() { executor.shutdownNow(); }

    @Scheduled(fixedDelayString = "${comatching.deposit-ingest.poll-ms:2000}")
    public void dispatch() {
        if (running.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    if (completedScans != null) {
                        try { completedScans.publish(); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                        catch (Exception e) { log.warn("KB 조회 회차 발행 보류: {}",e.getClass().getSimpleName()); }
                    }
                    publish();
                }
                catch (RuntimeException e) { log.error("입금 발행 대기 DB 처리 실패", e); }
                finally { running.set(false); }
            });
        }
    }

    public void publish() {
        String lease = UUID.randomUUID().toString();
        var rows = store.claim(lease);
        if (rows.isEmpty()) return;
        try {
            var sends = new ArrayList<CompletableFuture<?>>();
            for (var row : rows) sends.add(kafka.send(TOPIC, row.eventId(), row.payload()));
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.warn("KB 입금 관측 발행 보류: {}건, {}", rows.size(), e.getClass().getSimpleName());
            return; // Lease expiry retries these exact event IDs, including after restart.
        }
        store.published(lease);
    }
}
