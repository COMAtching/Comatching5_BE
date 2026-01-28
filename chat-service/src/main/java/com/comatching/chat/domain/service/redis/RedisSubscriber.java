package com.comatching.chat.domain.service.redis;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;

import com.comatching.chat.domain.dto.ChatMessageResponse;
import com.comatching.chat.domain.entity.ChatRoom;
import com.comatching.chat.domain.repository.ChatRoomRepository;
import com.comatching.chat.domain.service.block.BlockService;
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
	private final ChatRoomRepository chatRoomRepository;
	private final BlockService blockService;

	@Override
	public void onMessage(Message message, byte[] pattern) {
		try {
			String publishMessage = (String) redisTemplate.getStringSerializer().deserialize(message.getBody());

			ChatMessageResponse roomMessage = objectMapper.readValue(publishMessage, ChatMessageResponse.class);

			ChatRoom room = chatRoomRepository.findById(roomMessage.roomId()).orElse(null);
			if (room == null) {
				log.warn("채팅방을 찾을 수 없음: {}", roomMessage.roomId());
				return;
			}

			Long senderId = roomMessage.senderId();
			Long receiverId = getReceiverId(room, senderId);

			if (blockService.isBlocked(receiverId, senderId)) {
				log.debug("수신자가 발신자를 차단함 - receiverId: {}, senderId: {}", receiverId, senderId);
				return;
			}

			messagingTemplate.convertAndSend("/topic/chat.room." + roomMessage.roomId(), roomMessage);

		} catch (Exception e) {
			log.error("Redis 메시지 전달 오류: {}", e.getMessage());
		}
	}

	private Long getReceiverId(ChatRoom chatRoom, Long senderId) {
		return senderId.equals(chatRoom.getTargetUserId())
			? chatRoom.getInitiatorUserId()
			: chatRoom.getTargetUserId();
	}
}
