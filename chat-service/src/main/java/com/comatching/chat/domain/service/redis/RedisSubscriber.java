package com.comatching.chat.domain.service.redis;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;

import com.comatching.chat.domain.dto.ChatMessageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSubscriber implements MessageListener {

	private final ObjectMapper objectMapper;
	private final RedisTemplate<String, Object> redisTemplate;
	private final SimpMessageSendingOperations messagingTemplate;

	@Override
	public void onMessage(Message message, byte[] pattern) {
		try {
			String publishMessage = (String) redisTemplate.getStringSerializer().deserialize(message.getBody());

			ChatMessageResponse roomMessage = objectMapper.readValue(publishMessage, ChatMessageResponse.class);

			messagingTemplate.convertAndSend("/topic/chat.room." + roomMessage.roomId(), roomMessage);

		} catch (Exception e) {
			log.error("Redis 메시지 전달 오류: {}", e.getMessage());
		}
	}
}
