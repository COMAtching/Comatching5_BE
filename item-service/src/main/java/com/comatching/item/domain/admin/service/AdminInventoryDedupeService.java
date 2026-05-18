package com.comatching.item.domain.admin.service;

import java.time.Duration;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.item.domain.admin.dto.AdminInventoryUpdateRequest;
import com.comatching.item.global.exception.ItemErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminInventoryDedupeService {

	private static final long DEDUPE_TTL_SECONDS = 3L;
	private static final int MAX_REASON_LENGTH = 255;
	private static final String DEDUPE_KEY_PREFIX = "admin:inventory:dedupe";

	private final RedissonClient redissonClient;

	public void reserveOrThrow(Long memberId, AdminInventoryUpdateRequest request) {
		validateRequest(request);

		String key = String.join(":",
			DEDUPE_KEY_PREFIX,
			String.valueOf(memberId),
			request.itemType().name(),
			request.action().name(),
			String.valueOf(request.quantity()),
			request.reason()
		);

		RBucket<String> bucket = redissonClient.getBucket(key);
		boolean reserved = bucket.setIfAbsent("1", Duration.ofSeconds(DEDUPE_TTL_SECONDS));
		if (!reserved) {
			throw new BusinessException(ItemErrorCode.DUPLICATE_ADMIN_INVENTORY_ADJUSTMENT);
		}
	}

	private void validateRequest(AdminInventoryUpdateRequest request) {
		if (request == null || request.itemType() == null || request.action() == null || request.quantity() <= 0
			|| !StringUtils.hasText(request.reason()) || request.reason().length() > MAX_REASON_LENGTH) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE);
		}
	}
}
