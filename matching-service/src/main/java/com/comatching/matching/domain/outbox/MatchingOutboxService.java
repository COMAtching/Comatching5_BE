package com.comatching.matching.domain.outbox;

import com.comatching.common.dto.event.matching.MatchingSuccessEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatchingOutboxService {

    private final MatchingOutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(MatchingSuccessEvent event) {
        String payload = serialize(event);

        MatchingOutboxEvent outbox = MatchingOutboxEvent.pending(event.matchingId(), payload);

        outboxRepository.save(outbox);
    }

    private String serialize(MatchingSuccessEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("매칭 성공 이벤트 직렬화 실패. matchingId=" + event.matchingId(), e);
        }
    }
}
