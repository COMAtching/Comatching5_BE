package com.comatching.chat.domain.service.block;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.chat.domain.dto.BlockedUserResponse;
import com.comatching.chat.domain.entity.UserBlock;
import com.comatching.chat.domain.repository.UserBlockRepository;
import com.comatching.chat.global.exception.ChatErrorCode;
import com.comatching.common.exception.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BlockServiceImpl implements BlockService {

	private static final String BLOCK_CACHE_KEY_PREFIX = "user:block:";
	private static final Duration CACHE_TTL = Duration.ofMinutes(30);

	private final UserBlockRepository userBlockRepository;
	private final RedisTemplate<String, Object> redisTemplate;

	@Override
	public void blockUser(Long blockerUserId, Long blockedUserId) {
		if (blockerUserId.equals(blockedUserId)) {
			throw new BusinessException(ChatErrorCode.CANNOT_BLOCK_SELF);
		}

		if (userBlockRepository.existsByBlockerUserIdAndBlockedUserId(blockerUserId, blockedUserId)) {
			throw new BusinessException(ChatErrorCode.ALREADY_BLOCKED);
		}

		UserBlock userBlock = UserBlock.builder()
			.blockerUserId(blockerUserId)
			.blockedUserId(blockedUserId)
			.build();

		userBlockRepository.save(userBlock);
		evictBlockCache(blockerUserId);

		log.info("User {} blocked user {}", blockerUserId, blockedUserId);
	}

	@Override
	public void unblockUser(Long blockerUserId, Long blockedUserId) {
		if (!userBlockRepository.existsByBlockerUserIdAndBlockedUserId(blockerUserId, blockedUserId)) {
			throw new BusinessException(ChatErrorCode.NOT_BLOCKED);
		}

		userBlockRepository.deleteByBlockerUserIdAndBlockedUserId(blockerUserId, blockedUserId);
		evictBlockCache(blockerUserId);

		log.info("User {} unblocked user {}", blockerUserId, blockedUserId);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isBlocked(Long blockerUserId, Long blockedUserId) {
		Set<Long> blockedUserIds = getBlockedUserIds(blockerUserId);
		return blockedUserIds.contains(blockedUserId);
	}

	@Override
	@Transactional(readOnly = true)
	public List<BlockedUserResponse> getBlockedUsers(Long blockerUserId) {
		return userBlockRepository.findByBlockerUserId(blockerUserId).stream()
			.map(BlockedUserResponse::from)
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	@SuppressWarnings("unchecked")
	public Set<Long> getBlockedUserIds(Long blockerUserId) {
		String cacheKey = BLOCK_CACHE_KEY_PREFIX + blockerUserId;

		Set<Long> cachedBlockedIds = (Set<Long>) redisTemplate.opsForValue().get(cacheKey);
		if (cachedBlockedIds != null) {
			return cachedBlockedIds;
		}

		Set<Long> blockedUserIds = userBlockRepository.findByBlockerUserId(blockerUserId).stream()
			.map(UserBlock::getBlockedUserId)
			.collect(Collectors.toSet());

		redisTemplate.opsForValue().set(cacheKey, blockedUserIds, CACHE_TTL);

		return blockedUserIds;
	}

	private void evictBlockCache(Long userId) {
		String cacheKey = BLOCK_CACHE_KEY_PREFIX + userId;
		redisTemplate.delete(cacheKey);
	}
}
