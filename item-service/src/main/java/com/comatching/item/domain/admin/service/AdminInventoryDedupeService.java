package com.comatching.item.domain.admin.service;

import java.time.Duration;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.admin.dto.AdminInventoryUpdateRequest;
import com.comatching.item.global.exception.ItemErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminInventoryDedupeService {

	private static final long DEDUPE_TTL_SECONDS = 3L;
	private static final String DEDUPE_KEY_PREFIX = "admin:inventory:dedupe";

	private final RedissonClient redissonClient;
	private final AdminInventoryRequestValidator requestValidator;

	public void reserveOrThrow(Long memberId, AdminInventoryUpdateRequest request) {
		// 이 파이프라인의 첫 단계라 여기서만 검증한다 - adjust()는 항상 이 메서드 통과 후에만 호출된다
		requestValidator.validate(request);

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
}
