package com.comatching.chat.global.init;

import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.event.TransactionalEventListener;
import com.comatching.chat.domain.service.block.BlockServiceImpl.VisibilityChanged;
import java.nio.charset.StandardCharsets;

import com.comatching.chat.domain.service.redis.RedisSubscriber;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RedisInitializer implements CommandLineRunner {

	private final RedisMessageListenerContainer redisMessageListenerContainer;
	private final RedisSubscriber redisSubscriber;
	private final StringRedisTemplate strings;
	private final SimpMessagingTemplate sockets;
	private static final String VISIBILITY_CHANNEL = "chat.visibility";

	@TransactionalEventListener(fallbackExecution = true)
	public void visibilityChanged(VisibilityChanged change) {
		strings.convertAndSend(VISIBILITY_CHANNEL, change.memberId().toString());
	}

	@Override
	public void run(String... args) throws Exception {
		redisMessageListenerContainer.addMessageListener(redisSubscriber, new ChannelTopic("chatroom"));
		redisMessageListenerContainer.addMessageListener((message, pattern) -> {
			String memberId = new String(message.getBody(), StandardCharsets.UTF_8);
			sockets.convertAndSendToUser(memberId, "/queue/chat.visibility", "refresh");
		}, new ChannelTopic(VISIBILITY_CHANNEL));
	}
}
