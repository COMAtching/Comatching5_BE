package com.comatching.chat.domain.service.block;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.Map;

import com.comatching.chat.domain.entity.UserBlock;

import com.comatching.chat.domain.dto.BlockedUserResponse;

public interface BlockService {

	void blockUser(Long blockerUserId, Long blockedUserId);

	void unblockUser(Long blockerUserId, Long blockedUserId);

	boolean isBlocked(Long blockerUserId, Long blockedUserId);

	List<BlockedUserResponse> getBlockedUsers(Long blockerUserId);

	Optional<UserBlock> findRelation(Long memberId, Long otherMemberId);

	Map<Long, UserBlock> getRelations(Long memberId);

	LocalDateTime visibleAfter(Long memberId, Long otherMemberId);

	boolean hidesMessage(Long memberId, Long otherMemberId, LocalDateTime sentAt);
}
