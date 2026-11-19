package com.comatching.matching.infra.kafka;

import com.comatching.common.dto.event.matching.MatchingSuccessEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
public class MatchingEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String TOPIC = "matching-success-topic";

    public MatchingEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public CompletableFuture<SendResult<String, Object>> sendMatchingSuccess(
            MatchingSuccessEvent event
    ) {
        return kafkaTemplate
                .send(TOPIC, event.matchingId().toString(), event)
                .whenComplete((result, ex) -> {
                    // 기존 성공·실패 로그
                });
    }
}
