package com.comatching.chat.domain.service.chat;

import java.util.List;

import org.springframework.data.domain.Pageable;

import com.comatching.chat.domain.dto.ChatMessageRequest;
import com.comatching.chat.domain.dto.ChatMessageResponse;

public interface ChatService {

	ChatMessageResponse markAsRead(String roomId, Long memberId);

	ChatMessageResponse processMessage(ChatMessageRequest request);

	List<ChatMessageResponse> getChatHistory(String roomId, Long userId, Pageable pageable);
}
