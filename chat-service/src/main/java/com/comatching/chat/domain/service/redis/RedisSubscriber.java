package com.comatching.chat.domain.service.redis;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;

import com.comatching.chat.domain.dto.ChatMessageResponse;
import com.comatching.chat.domain.entity.ChatRoom;
import com.comatching.chat.domain.repository.ChatRoomRepository;
import com.comatching.chat.domain.enums.MessageType;
import com.comatching.chat.domain.service.block.BlockService;
import com.comatching.chat.domain.service.chat.ChatService;
import com.comatching.chat.domain.service.presence.ChatRoomPresenceTracker;
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
	private final ChatRoomPresenceTracker presenceTracker;
	private final ChatService chatService;
	private final RedisPublisher redisPublisher;

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

			autoReadIfReceiverIsViewing(roomMessage, receiverId);

		} catch (Exception e) {
			log.error("Redis 메시지 전달 오류: {}", e.getMessage());
		}
	}

	/**
	 * 수신자가 지금 이 인스턴스에서 그 방을 보고 있으면 서버가 대신 읽음 처리한다.
	 *
	 * 읽음 갱신이 클라이언트의 READ 전송에만 의존하면, 방을 켜둔 채 받은 메시지는
	 * 수신자가 방을 나갔다 다시 들어올 때까지 "안 읽음" 으로 남는다.
	 *
	 * READ 는 sender 의 인스턴스가 아니라 여기(수신자 세션을 쥔 인스턴스)서 Redis 로
	 * 발행한다. 그래야 발신자가 다른 인스턴스에 붙어 있어도 읽음 전파를 받는다.
	 * READ 메시지 자체에는 반응하지 않으므로 발행이 꼬리를 물지 않는다.
	 */
	private void autoReadIfReceiverIsViewing(ChatMessageResponse roomMessage, Long receiverId) {
		if (roomMessage.type() == MessageType.READ) {
			return;
		}
		if (!presenceTracker.isViewing(roomMessage.roomId(), receiverId)) {
			return;
		}

		try {
			ChatMessageResponse readResponse = chatService.markAsRead(roomMessage.roomId(), receiverId);
			redisPublisher.publish(new ChannelTopic("chatroom"), readResponse);
		} catch (Exception e) {
			// 자동 읽음은 편의 기능이다. 실패해도 메시지 전달을 되돌릴 이유는 없다.
			log.warn("자동 읽음 처리 실패 - roomId: {}, receiverId: {}", roomMessage.roomId(), receiverId, e);
		}
	}

	private Long getReceiverId(ChatRoom chatRoom, Long senderId) {
		return senderId.equals(chatRoom.getTargetUserId())
			? chatRoom.getInitiatorUserId()
			: chatRoom.getTargetUserId();
	}
}
