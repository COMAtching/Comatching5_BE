package com.comatching.chat.domain.service.redis;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Service;

import com.comatching.chat.domain.dto.ChatMessageResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RedisPublisher {

	private final RedisTemplate<String, Object> redisTemplate;

	public void publish(ChannelTopic topic, ChatMessageResponse message) {
		redisTemplate.convertAndSend(topic.getTopic(), message);
	}
}
