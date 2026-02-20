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

		Object cachedValue = redisTemplate.opsForValue().get(cacheKey);

		if (cachedValue != null) {
			// Redis 직렬화(JSON) 과정에서 Set이 List(Array)로 변환되어 저장된 경우 처리
			if (cachedValue instanceof java.util.List) {
				return new java.util.HashSet<>((java.util.List<Long>) cachedValue);
			}
			// 설정에 따라 Set 타입 그대로 복원된 경우
			if (cachedValue instanceof Set) {
				return (Set<Long>) cachedValue;
			}
			// 그 외의 타입이 들어있다면 무시하고 DB 조회로 넘어갑니다.
		}

		// DB 조회 로직 (기존과 동일)
		Set<Long> blockedUserIds = userBlockRepository.findByBlockerUserId(blockerUserId).stream()
			.map(UserBlock::getBlockedUserId)
			.collect(Collectors.toSet());

		// 캐시 저장
		redisTemplate.opsForValue().set(cacheKey, blockedUserIds, CACHE_TTL);

		return blockedUserIds;
	}

	private void evictBlockCache(Long userId) {
		String cacheKey = BLOCK_CACHE_KEY_PREFIX + userId;
		redisTemplate.delete(cacheKey);
	}
}
