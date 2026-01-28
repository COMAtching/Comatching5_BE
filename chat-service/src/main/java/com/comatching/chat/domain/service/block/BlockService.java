package com.comatching.chat.domain.service.block;

import java.util.List;
import java.util.Set;

import com.comatching.chat.domain.dto.BlockedUserResponse;

public interface BlockService {

	void blockUser(Long blockerUserId, Long blockedUserId);

	void unblockUser(Long blockerUserId, Long blockedUserId);

	boolean isBlocked(Long blockerUserId, Long blockedUserId);

	List<BlockedUserResponse> getBlockedUsers(Long blockerUserId);

	Set<Long> getBlockedUserIds(Long blockerUserId);
}
