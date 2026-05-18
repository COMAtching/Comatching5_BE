package com.comatching.chat.domain.service.chat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
		validateRoomMember(room, memberId);

		LocalDateTime readAt = LocalDateTime.now();
		chatRoomRepository.touchLastReadAt(roomId, memberId, readAt);

		return new ChatMessageResponse(
			null,
			roomId,
			memberId,
			null,
			MessageType.READ,
			readAt,
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
		validateRoomMember(chatRoom, request.senderId());

		Long receiverId = getReceiverId(chatRoom, request.senderId());
		boolean isBlockedByReceiver = blockService.isBlocked(receiverId, request.senderId());

		String finalContent = resolveContent(request);
		ChatMessage savedMessage = saveChatMessage(request, finalContent);
		LocalDateTime sentAt = savedMessage.getCreatedAt() != null ? savedMessage.getCreatedAt() : now;
		log.info(
			"chat.message.saved roomId={} messageId={} senderId={} type={} createdAt={}",
			savedMessage.getRoomId(),
			savedMessage.getId(),
			savedMessage.getSenderId(),
			savedMessage.getType(),
			sentAt
		);

		if (!isBlockedByReceiver) {
			updateChatRoomInfo(chatRoom, request, savedMessage.getId(), sentAt);
			publishNotificationEvent(chatRoom, request, sentAt);
		}

		return ChatMessageResponse.from(savedMessage, 1);
	}

	private void validateRoomMember(ChatRoom chatRoom, Long memberId) {
		if (!chatRoom.getInitiatorUserId().equals(memberId) && !chatRoom.getTargetUserId().equals(memberId)) {
			throw new BusinessException(GeneralErrorCode.FORBIDDEN);
		}
	}

	private Long getReceiverId(ChatRoom chatRoom, Long senderId) {
		return senderId.equals(chatRoom.getTargetUserId())
			? chatRoom.getInitiatorUserId()
			: chatRoom.getTargetUserId();
	}

	@Transactional(readOnly = true)
	public List<ChatMessageResponse> getChatHistory(String roomId, Long memberId, Pageable pageable) {
		log.info(
			"chat.history.request roomId={} memberId={} page={} size={}",
			roomId,
			memberId,
			getPageNumber(pageable),
			getPageSize(pageable)
		);

		ChatRoom room = chatRoomRepository.findById(roomId)
			.orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_EXIST_CHATROOM));

		LocalDateTime otherUserLastReadAt = room.getOtherUserLastReadAt(memberId);
		if (!room.getInitiatorUserId().equals(memberId) && !room.getTargetUserId().equals(memberId)) {
			throw new BusinessException(GeneralErrorCode.FORBIDDEN);
		}

		Pageable latestFirstPageable = toLatestFirstPageable(pageable);
		List<ChatMessage> messages = new ArrayList<>(chatMessageRepository.findByRoomId(roomId, latestFirstPageable));
		Collections.reverse(messages);

		List<ChatMessageResponse> responses = messages.stream()
			.map(msg -> {
				int readCount = calculateReadCount(msg, otherUserLastReadAt);
				return ChatMessageResponse.from(msg, readCount);
			})
			.toList();

		log.info(
			"chat.history.response roomId={} memberId={} count={} firstMessageId={} lastMessageId={}",
			roomId,
			memberId,
			responses.size(),
			getFirstMessageId(responses),
			getLastMessageId(responses)
		);

		return responses;
	}

	private int calculateReadCount(ChatMessage message, LocalDateTime otherUserLastReadAt) {
		return message.getCreatedAt().isAfter(otherUserLastReadAt) ? 1 : 0;
	}

	private Pageable toLatestFirstPageable(Pageable pageable) {
		Sort latestFirstSort = Sort.by(Sort.Direction.DESC, "createdAt")
			.and(Sort.by(Sort.Direction.DESC, "id"));

		if (pageable == null || pageable.isUnpaged()) {
			return Pageable.unpaged(latestFirstSort);
		}

		return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), latestFirstSort);
	}

	private int getPageNumber(Pageable pageable) {
		return pageable == null || pageable.isUnpaged() ? -1 : pageable.getPageNumber();
	}

	private int getPageSize(Pageable pageable) {
		return pageable == null || pageable.isUnpaged() ? -1 : pageable.getPageSize();
	}

	private String getFirstMessageId(List<ChatMessageResponse> responses) {
		return responses.isEmpty() ? null : responses.get(0).id();
	}

	private String getLastMessageId(List<ChatMessageResponse> responses) {
		return responses.isEmpty() ? null : responses.get(responses.size() - 1).id();
	}

	private void updateChatRoomInfo(ChatRoom chatRoom, ChatMessageRequest request, String messageId, LocalDateTime now) {
		String previewContent = (request.type() == MessageType.IMAGE) ? "사진을 보냈습니다." : request.content();

		boolean updated = chatRoomRepository.updateLastMessageIfLatest(chatRoom.getId(), previewContent, now);
		log.info(
			"chat.room.summary.updated roomId={} messageId={} updated={} lastMessageTime={}",
			chatRoom.getId(),
			messageId,
			updated,
			now
		);
		chatRoomRepository.touchLastReadAt(chatRoom.getId(), request.senderId(), now);
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
				log.error(
					"chat.notification.kafka.send.failure roomId={} targetUserId={} error={}",
					request.roomId(),
					getReceiverId(room, request.senderId()),
					e.getMessage()
				);
			}
		}
	}
}
