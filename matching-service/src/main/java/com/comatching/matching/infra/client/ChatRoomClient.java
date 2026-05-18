package com.comatching.matching.infra.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.comatching.common.dto.chat.ChatRoomReferenceResponse;

@FeignClient(name = "chat-service", path = "/api/internal/chat/rooms", url = "${chat-service.url}")
public interface ChatRoomClient {

	@PostMapping("/references")
	List<ChatRoomReferenceResponse> getChatRoomReferences(@RequestBody List<Long> matchingIds);
}
