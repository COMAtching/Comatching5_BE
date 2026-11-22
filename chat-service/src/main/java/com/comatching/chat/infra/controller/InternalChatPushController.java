package com.comatching.chat.infra.controller;

import com.comatching.chat.domain.repository.ChatMessageRepository;
import com.comatching.chat.domain.repository.ChatRoomRepository;
import com.comatching.chat.domain.repository.UserBlockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/chat/messages")
public class InternalChatPushController {
    private final ChatMessageRepository messages;
    private final ChatRoomRepository rooms;
    private final UserBlockRepository blocks;

    @GetMapping("/{messageId}/push-eligible")
    public boolean eligible(@PathVariable String messageId, @RequestParam Long targetUserId) {
        var message = messages.findById(messageId).orElse(null);
        if (message == null || message.getPushOutbox() == null
                || !targetUserId.equals(message.getPushOutbox().targetUserId())) return false;

        var room = rooms.findById(message.getRoomId()).orElse(null);
        if (room == null) return false;
        var readAt = room.getOtherUserLastReadAt(message.getSenderId());
        if (readAt != null && !readAt.isBefore(message.getCreatedAt())) return false;
        return !blocks.existsByBlockerUserIdAndBlockedUserId(targetUserId, message.getSenderId());
    }
}
