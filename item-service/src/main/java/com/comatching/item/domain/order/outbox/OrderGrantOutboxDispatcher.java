package com.comatching.item.domain.order.outbox;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderGrantOutboxDispatcher {
    private final OrderGrantOutboxRepository outbox;
    private final KafkaTemplate<String, Object> kafka;

    @Scheduled(fixedDelayString = "${comatching.outbox.poll-interval-ms:1000}")
    public void dispatch() {
        for (var entry : outbox.ready()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            String token = UUID.randomUUID().toString();
            if (!outbox.claim(entry.id(), token)) {
                continue;
            }
            try {
                kafka.send(entry.topic(), Long.toString(entry.orderId()), entry.payload().getBytes(StandardCharsets.UTF_8))
                        .get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                log.warn("주문 이벤트 발행 재시도. orderId={} topic={}", entry.orderId(), entry.topic(), e);
                outbox.retry(entry, token);
                continue;
            }
            if (!outbox.published(entry.id(), token)) {
                log.warn("발행 완료 기록 전에 선점자가 바뀜. orderId={}", entry.orderId());
            }
        }
    }
}
