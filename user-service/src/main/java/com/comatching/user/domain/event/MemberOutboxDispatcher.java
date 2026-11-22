package com.comatching.user.domain.event;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class MemberOutboxDispatcher {
    private final MemberOutboxRepository outbox;
    private final KafkaTemplate<String, Object> kafka;

    // DB 트랜잭션 밖에서 ACK를 기다린다. 각 상태 UPDATE는 독립적으로 커밋된다.
    @Scheduled(fixedDelayString = "${comatching.outbox.poll-interval-ms:1000}")
    public void dispatch() {
        for (var entry : outbox.ready()) {
            if (Thread.currentThread().isInterrupted()) return;
            String token = UUID.randomUUID().toString();
            if (!outbox.claim(entry.id(), token)) continue;
            try {
                kafka.send(entry.topic(), Long.toString(entry.memberId()),
                        entry.payload().getBytes(StandardCharsets.UTF_8)).get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // PROCESSING 행은 선점 기한이 지나면 다시 선택된다.
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                log.warn("회원 이벤트 발행 재시도. outboxId={} topic={}", entry.id(), entry.topic(), e);
                outbox.retry(entry, token);
                continue;
            }
            if (!outbox.published(entry.id(), token)) {
                log.warn("완료 처리 전에 선점자가 바뀜. outboxId={}", entry.id());
            }
        }
    }
}
