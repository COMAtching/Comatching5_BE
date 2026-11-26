package com.comatching.chat.domain.service.chat;

import com.comatching.common.operations.Feature;
import com.comatching.common.operations.FeatureGate;

import com.comatching.chat.domain.dto.ChatMessageRequest;
import com.comatching.chat.domain.dto.ChatMessageResponse;
import com.comatching.chat.domain.entity.ChatMessage;
import com.comatching.chat.domain.entity.ChatRoom;
import com.comatching.chat.domain.enums.MessageType;
import com.comatching.chat.domain.repository.ChatMessageRepository;
import com.comatching.chat.domain.repository.ChatRoomRepository;
import com.comatching.chat.domain.service.block.BlockService;
import com.comatching.chat.global.exception.ChatErrorCode;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ChatServiceImpl implements ChatService {
    private final FeatureGate features;

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final S3Service s3Service;
    private final BlockService blockService;

    @Override
    public ChatMessageResponse markAsRead(String roomId, Long memberId) {
        features.requireOpen(Feature.CHAT);
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_EXIST_CHATROOM));
        blockService.visibleAfter(memberId, room.otherMemberId(memberId));

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

        features.requireOpen(Feature.CHAT);
        LocalDateTime sentAt = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);

        ChatRoom chatRoom = chatRoomRepository.findById(request.roomId())
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_EXIST_CHATROOM));
        Long receiverId = chatRoom.otherMemberId(request.senderId());
        LocalDateTime senderCutoff = blockService.visibleAfter(request.senderId(), receiverId);
        var receiverRelation = blockService.findRelation(receiverId, request.senderId()).orElse(null);
        boolean isBlockedByReceiver = receiverRelation != null && receiverRelation.isActive();
        if (!isBlockedByReceiver) {
            // MongoDB 날짜는 밀리초 단위다. 이미 해제된 상태에서 접수한 첫 메시지도 보이게 한다.
            sentAt = afterCutoff(sentAt, senderCutoff);
            if (receiverRelation != null) sentAt = afterCutoff(sentAt, receiverRelation.getUnblockedAt());
        }

        String finalContent = resolveContent(request);

        boolean needsPush = !isBlockedByReceiver
                && (request.type() == MessageType.TALK || request.type() == MessageType.IMAGE);

        ChatMessage.PushOutbox pushOutbox = needsPush
                ? ChatMessage.PushOutbox.pending(receiverId, request.senderNickname())
                : null;

        ChatMessage savedMessage = saveChatMessage(request, finalContent, pushOutbox, sentAt);

        sentAt = savedMessage.getCreatedAt();
        log.info(
                "chat.message.saved roomId={} messageId={} senderId={} type={} createdAt={}",
                savedMessage.getRoomId(),
                savedMessage.getId(),
                savedMessage.getSenderId(),
                savedMessage.getType(),
                sentAt
        );

        updateChatRoomInfo(chatRoom, request, savedMessage.getId(), sentAt);

        return ChatMessageResponse.from(savedMessage, 1);
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

        LocalDateTime visibleAfter = blockService.visibleAfter(memberId, room.otherMemberId(memberId));
        LocalDateTime otherUserLastReadAt = room.getOtherUserLastReadAt(memberId);
        Pageable latestFirstPageable = toLatestFirstPageable(pageable);
        List<ChatMessage> messages = new ArrayList<>(visibleAfter == null
                ? chatMessageRepository.findByRoomId(roomId, latestFirstPageable)
                : chatMessageRepository.findByRoomIdAndCreatedAtAfter(roomId, visibleAfter, latestFirstPageable));
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
            String imageUrl = s3Service.attachChat(request.senderId(), request.roomId(), request.content());
            features.requireOpen(Feature.CHAT);
            return imageUrl;
        }
        return request.content();
    }

    private LocalDateTime afterCutoff(LocalDateTime sentAt, LocalDateTime cutoff) {
        return cutoff != null && !sentAt.isAfter(cutoff) ? cutoff.plus(1, ChronoUnit.MILLIS) : sentAt;
    }

    private ChatMessage saveChatMessage(ChatMessageRequest request, String content, ChatMessage.PushOutbox pushOutbox,
                                        LocalDateTime sentAt) {
        ChatMessage message = ChatMessage.builder()
                .roomId(request.roomId())
                .senderId(request.senderId())
                .content(content)
                .type(request.type())
                .pushOutbox(pushOutbox)
                .createdAt(sentAt)
                .build();
        return chatMessageRepository.save(message);
    }
}
