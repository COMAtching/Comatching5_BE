package com.comatching.chat.infra.kafka;

import com.comatching.chat.domain.entity.ChatMessage;
import com.comatching.chat.domain.enums.MessageType;
import com.comatching.common.dto.event.chat.ChatMessageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatPushOutboxDispatcher {

    private final MongoTemplate mongoTemplate;
    private final ChatEventProducer chatEventProducer;

    @Scheduled(fixedDelayString = "${comatching.chat.push-outbox.poll-ms:1000}")
    public void dispatch() {
        for (int i = 0; i < 50; i++) {
            String token = UUID.randomUUID().toString();
            ChatMessage message = claim(token);
            if (message == null) return;

            try {
                var result = chatEventProducer.send(toEvent(message)).get(10, TimeUnit.SECONDS);
                var metadata = result.getRecordMetadata();
                log.info("chat.notification.kafka.send.success roomId={} targetUserId={} topic={} partition={} offset={} messageId={}",
                        message.getRoomId(), message.getPushOutbox().targetUserId(), metadata.topic(),
                        metadata.partition(), metadata.offset(), message.getId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // 작업 기한이 지나면 다른 워커가 이어받는다.
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                log.warn("chat.notification.kafka.send.failure roomId={} targetUserId={} messageId={}",
                        message.getRoomId(), message.getPushOutbox().targetUserId(), message.getId(), e);
                finish(message.getId(), token, new Update()
                        .set("pushOutbox.status", "PENDING")
                        .set("pushOutbox.availableAt", Instant.now().plusSeconds(30))
                        .unset("pushOutbox.claimToken"));
                continue;
            }

            finish(message.getId(), token, new Update()
                    .set("pushOutbox.status", "PUBLISHED")
                    .unset("pushOutbox.availableAt")
                    .unset("pushOutbox.claimToken"));
        }
    }

    private ChatMessage claim(String token) {
        Instant now = Instant.now();

        Query due = Query.query(Criteria.where("pushOutbox.availableAt").lte(now)
                        .and("pushOutbox.status").in("PENDING", "PROCESSING"))
                .with(Sort.by(Sort.Direction.ASC, "pushOutbox.availableAt"));

        Update claim = new Update()
                .set("pushOutbox.status", "PROCESSING")
                .set("pushOutbox.claimToken", token)
                .set("pushOutbox.availableAt", now.plusSeconds(120));

        return mongoTemplate.findAndModify(
                due, claim, FindAndModifyOptions.options().returnNew(true), ChatMessage.class
        );
    }

    private void finish(String id, String token, Update update) {
        Query mine = Query.query(Criteria.where("_id").is(id)
                .and("pushOutbox.status").is("PROCESSING")
                .and("pushOutbox.claimToken").is(token));

        if (mongoTemplate.updateFirst(mine, update, ChatMessage.class).getMatchedCount() == 0) {
            log.info("채팅 푸시 작업 소유권 변경 messageId={}", id);
        }
    }

    private ChatMessageEvent toEvent(ChatMessage message) {
        var push = message.getPushOutbox();
        String preview = message.getType() == MessageType.IMAGE
                ? "사진을 보냈습니다."
                : message.getContent();

        return new ChatMessageEvent(
                push.targetUserId(), push.senderNickname(), message.getRoomId(),
                preview, message.getCreatedAt().toString(), "CHAT", message.getId()
        );
    }

}
