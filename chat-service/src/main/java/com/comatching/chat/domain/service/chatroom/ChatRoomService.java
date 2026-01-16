package com.comatching.chat.domain.service.chatroom;

import java.util.List;

import com.comatching.chat.domain.dto.ChatRoomResponse;
import com.comatching.common.dto.event.matching.MatchingSuccessEvent;

public interface ChatRoomService {

	void createChatRoom(MatchingSuccessEvent event);

	List<ChatRoomResponse> getMyChatRooms(Long memberId);
}
