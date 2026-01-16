package com.comatching.chat.global.init;

import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import com.comatching.chat.domain.service.redis.RedisSubscriber;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RedisInitializer implements CommandLineRunner {

	private final RedisMessageListenerContainer redisMessageListenerContainer;
	private final RedisSubscriber redisSubscriber;

	@Override
	public void run(String... args) throws Exception {
		redisMessageListenerContainer.addMessageListener(redisSubscriber, new ChannelTopic("chatroom"));
	}
}
