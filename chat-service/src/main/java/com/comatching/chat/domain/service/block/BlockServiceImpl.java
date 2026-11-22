package com.comatching.chat.domain.service.block;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.chat.domain.dto.BlockedUserResponse;
import com.comatching.chat.domain.entity.UserBlock;
import com.comatching.chat.domain.repository.UserBlockRepository;
import com.comatching.chat.global.exception.ChatErrorCode;
import com.comatching.common.exception.BusinessException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class BlockServiceImpl implements BlockService {

	private final UserBlockRepository userBlockRepository;
	private final ApplicationEventPublisher events;

	public record VisibilityChanged(Long memberId) {}

	@Override
	public void blockUser(Long blockerUserId, Long blockedUserId) {
		if (blockerUserId.equals(blockedUserId)) {
			throw new BusinessException(ChatErrorCode.CANNOT_BLOCK_SELF);
		}
		if (userBlockRepository.reactivate(blockerUserId, blockedUserId) == 0) {
			try {
				userBlockRepository.insert(new UserBlock(blockerUserId, blockedUserId));
			} catch (DuplicateKeyException duplicate) {
				throw new BusinessException(ChatErrorCode.ALREADY_BLOCKED);
			}
		}
		events.publishEvent(new VisibilityChanged(blockerUserId));
	}

	@Override
	public void unblockUser(Long blockerUserId, Long blockedUserId) {
		if (userBlockRepository.release(blockerUserId, blockedUserId) == 0) {
			throw new BusinessException(ChatErrorCode.NOT_BLOCKED);
		}
		events.publishEvent(new VisibilityChanged(blockerUserId));
	}

	@Override
	public Optional<UserBlock> findRelation(Long memberId, Long otherMemberId) {
		return userBlockRepository.findByBlockerUserIdAndBlockedUserId(memberId, otherMemberId);
	}

	@Override
	public boolean isBlocked(Long memberId, Long otherMemberId) {
		return findRelation(memberId, otherMemberId)
			.filter(UserBlock::isActive).isPresent();
	}

	@Override
	public LocalDateTime visibleAfter(Long memberId, Long otherMemberId) {
		return findRelation(memberId, otherMemberId)
			.map(relation -> {
				if (relation.isActive()) throw new BusinessException(ChatErrorCode.NOT_EXIST_CHATROOM);
				return relation.getUnblockedAt();
			}).orElse(null);
	}

	@Override
	public boolean hidesMessage(Long memberId, Long otherMemberId, LocalDateTime sentAt) {
		return findRelation(memberId, otherMemberId)
			.filter(relation -> relation.hides(sentAt)).isPresent();
	}

	@Override
	public List<BlockedUserResponse> getBlockedUsers(Long blockerUserId) {
		return userBlockRepository.findByBlockerUserId(blockerUserId).stream()
			.filter(UserBlock::isActive)
			.map(BlockedUserResponse::from).toList();
	}

	@Override
	public Map<Long, UserBlock> getRelations(Long memberId) {
		return userBlockRepository.findByBlockerUserId(memberId).stream()
			.collect(Collectors.toMap(UserBlock::getBlockedUserId, Function.identity()));
	}
}
