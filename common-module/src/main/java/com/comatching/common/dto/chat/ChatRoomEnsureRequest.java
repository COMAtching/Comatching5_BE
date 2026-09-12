package com.comatching.common.dto.chat;

/**
 * 매칭에 대응하는 채팅방이 있는지 확인하고 없으면 만들어 달라는 요청.
 *
 * 방 생성은 원래 매칭 성공 Kafka 이벤트로만 일어났는데, 발행이 유실되면
 * 그 매칭은 영구히 방이 없었다. 조회 시점에 생성까지 맡기면 유실분이
 * 자동으로 복구된다. 생성에 필요한 참여자 정보는 매칭 이력이 알고 있으므로
 * matching-service 가 채워 보낸다.
 */
public record ChatRoomEnsureRequest(
	Long matchingId,
	Long initiatorUserId,
	Long targetUserId
) {
}
