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
import com.comatching.chat.domain.service.block.BlockService;
import com.comatching.chat.global.exception.ChatErrorCode;
import com.comatching.chat.infra.kafka.ChatEventProducer;
import com.comatching.common.dto.event.chat.ChatMessageEvent;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.service.S3Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ChatServiceImpl implements ChatService {

	private final ChatRoomRepository chatRoomRepository;
	private final ChatMessageRepository chatMessageRepository;
	private final S3Service s3Service;
	private final ChatEventProducer chatEventProducer;
	private final BlockService blockService;

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

		ChatRoom chatRoom = chatRoomRepository.findById(request.roomId())
			.orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_EXIST_CHATROOM));

		Long receiverId = getReceiverId(chatRoom, request.senderId());
		boolean isBlockedByReceiver = blockService.isBlocked(receiverId, request.senderId());

		String finalContent = resolveContent(request);
		ChatMessage savedMessage = saveChatMessage(request, finalContent);

		if (!isBlockedByReceiver) {
			updateChatRoomInfo(chatRoom, request, now);
			publishNotificationEvent(chatRoom, request, now);
		}

		return ChatMessageResponse.from(savedMessage, 1);
	}

	private Long getReceiverId(ChatRoom chatRoom, Long senderId) {
		return senderId.equals(chatRoom.getTargetUserId())
			? chatRoom.getInitiatorUserId()
			: chatRoom.getTargetUserId();
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

	private void updateChatRoomInfo(ChatRoom chatRoom, ChatMessageRequest request, LocalDateTime now) {
		String previewContent = (request.type() == MessageType.IMAGE) ? "사진을 보냈습니다." : request.content();

		chatRoom.updateLastMessageInfo(previewContent, now);
		chatRoom.updateLastReadAt(request.senderId(), now);

		chatRoomRepository.save(chatRoom);
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

			try {
				Long targetId = request.senderId().equals(room.getTargetUserId())
					? room.getInitiatorUserId()
					: room.getTargetUserId();

				String preview = (request.type() == MessageType.IMAGE) ? "사진을 보냈습니다." : request.content();

				chatEventProducer.send(new ChatMessageEvent(
					targetId,
					request.senderNickname(),
					request.roomId(),
					preview,
					now.toString(),
					"CHAT"
				));

			} catch (Exception e) {
				log.error("⚠️ 알림 이벤트 발행 실패 (메시지는 정상 저장됨) - RoomId: {}, Error: {}",
					request.roomId(), e.getMessage());

			}
		}
	}
}
