package com.comatching.matching.infra.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.comatching.common.dto.chat.ChatRoomEnsureRequest;
import com.comatching.common.dto.chat.ChatRoomReferenceResponse;

@FeignClient(name = "chat-service", path = "/api/internal/chat/rooms", url = "${chat-service.url}")
public interface ChatRoomClient {

	@PostMapping("/references")
	List<ChatRoomReferenceResponse> getChatRoomReferences(@RequestBody List<Long> matchingIds);

	// 방이 없는 매칭은 chat-service 가 그 자리에서 만든다. Kafka 발행이 유실된
	// 매칭도 이력 조회 시점에 복구된다.
	@PostMapping("/ensure")
	List<ChatRoomReferenceResponse> ensureChatRooms(@RequestBody List<ChatRoomEnsureRequest> requests);
}
