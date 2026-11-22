package com.comatching.chat.infra.kafka;

import com.comatching.common.dto.event.chat.ChatMessageEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
public class ChatEventProducer {

    // 토픽 이름은 "무슨 일이 있었나"(채팅 메시지가 보내졌다)로 짓는다. 누가 받아서
    // 무엇을 할지(알림 발송)는 구독자의 몫이라 이름에 담지 않는다.
    private static final String CHAT_MESSAGE_SENT_TOPIC = "chat-message-sent";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public CompletableFuture<SendResult<String, Object>> send(ChatMessageEvent event) {
        return kafkaTemplate.send(CHAT_MESSAGE_SENT_TOPIC, event.roomId(), event);
    }
}
