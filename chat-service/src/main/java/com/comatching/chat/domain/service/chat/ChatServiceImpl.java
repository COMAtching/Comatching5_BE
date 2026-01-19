package com.comatching.chat.domain.service.chat;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.chat.domain.dto.ChatMessageRequest;
import com.comatching.chat.domain.dto.ChatMessageResponse;
import com.comatching.chat.domain.entity.ChatMessage;
import com.comatching.chat.domain.entity.ChatRoom;
import com.comatching.chat.domain.enums.MessageType;
import com.comatching.chat.domain.repository.ChatMessageRepository;
import com.comatching.chat.domain.repository.ChatRoomRepository;
import com.comatching.chat.global.exception.ChatErrorCode;
import com.comatching.chat.infra.kafka.ChatEventProducer;
import com.comatching.common.dto.event.chat.ChatMessageEvent;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.service.S3Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class ChatServiceImpl implements ChatService {

	private final ChatRoomRepository chatRoomRepository;
	private final ChatMessageRepository chatMessageRepository;
	private final S3Service s3Service;
	private final ChatEventProducer chatEventProducer;

	@Override
	public ChatMessageResponse markAsRead(String roomId, Long memberId) {
		ChatRoom room = chatRoomRepository.findById(roomId)
			.orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_EXIST_CHATROOM));

		room.updateLastReadAt(memberId, LocalDateTime.now());
		chatRoomRepository.save(room);

		return new ChatMessageResponse(
			null,
			roomId,
			memberId,
			null,
			MessageType.READ,
			LocalDateTime.now(),
			0
		);
	}

	@Override
	public ChatMessageResponse processMessage(ChatMessageRequest request) {

		if (request.type() == MessageType.READ) {
			return markAsRead(request.roomId(), request.senderId());
		}

		LocalDateTime now = LocalDateTime.now();

		ChatRoom chatRoom = updateChatRoomInfo(request, now);

		String finalContent = resolveContent(request);

		ChatMessage savedMessage = saveChatMessage(request, finalContent);

		publishNotificationEvent(chatRoom, request, now);

		return ChatMessageResponse.from(savedMessage, 1);
	}

	@Transactional(readOnly = true)
	public List<ChatMessageResponse> getChatHistory(String roomId, Long memberId, Pageable pageable) {

		ChatRoom room = chatRoomRepository.findById(roomId)
			.orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_EXIST_CHATROOM));

		LocalDateTime otherUserLastReadAt = room.getOtherUserLastReadAt(memberId);
		if (!room.getInitiatorUserId().equals(memberId) && !room.getTargetUserId().equals(memberId)) {
			throw new BusinessException(GeneralErrorCode.FORBIDDEN);
		}

		return chatMessageRepository.findByRoomIdOrderByCreatedAtDesc(roomId, pageable).stream()
			.map(msg -> {
				int readCount = msg.getCreatedAt().isBefore(otherUserLastReadAt) ? 0 : 1;
				return ChatMessageResponse.from(msg, readCount);
			})
			.toList();
	}

	private ChatRoom updateChatRoomInfo(ChatMessageRequest request, LocalDateTime now) {
		ChatRoom chatRoom = chatRoomRepository.findById(request.roomId())
			.orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_EXIST_CHATROOM));

		String previewContent = (request.type() == MessageType.IMAGE) ? "사진을 보냈습니다." : request.content();

		chatRoom.updateLastMessageInfo(previewContent, now);
		chatRoom.updateLastReadAt(request.senderId(), now);

		return chatRoom;
	}

	private String resolveContent(ChatMessageRequest request) {
		if (request.type() == MessageType.IMAGE) {
			return s3Service.getFileUrl(request.content());
		}
		return request.content();
	}

	private ChatMessage saveChatMessage(ChatMessageRequest request, String content) {
		ChatMessage chatMessage = ChatMessage.builder()
			.roomId(request.roomId())
			.senderId(request.senderId())
			.content(content)
			.type(request.type())
			.build();
		return chatMessageRepository.save(chatMessage);
	}

	private void publishNotificationEvent(ChatRoom room, ChatMessageRequest request, LocalDateTime now) {

		if (request.type() == MessageType.TALK || request.type() == MessageType.IMAGE) {

			Long senderId = request.senderId();
			Long receiverId;

			if (senderId.equals(room.getTargetUserId())) {
				receiverId = room.getInitiatorUserId();
			} else {
				receiverId = room.getTargetUserId();
			}

			String preview = (request.type() == MessageType.IMAGE) ? "사진을 보냈습니다." : request.content();

			chatEventProducer.send(new ChatMessageEvent(
				receiverId,
				request.senderNickname(),
				request.roomId(),
				preview,
				now.toString(),
				"CHAT"
			));
		}
	}
}
