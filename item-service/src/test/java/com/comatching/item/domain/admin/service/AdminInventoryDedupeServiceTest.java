package com.comatching.item.domain.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.admin.dto.AdminInventoryAction;
import com.comatching.item.domain.admin.dto.AdminInventoryUpdateRequest;
import com.comatching.item.global.exception.ItemErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminInventoryDedupeService 테스트")
class AdminInventoryDedupeServiceTest {

	@InjectMocks
	private AdminInventoryDedupeService adminInventoryDedupeService;

	@Mock
	private RedissonClient redissonClient;

	@Mock
	private RBucket<String> bucket;

	@Test
	@DisplayName("동일 조정 요청을 3초 TTL 키로 예약한다")
	void shouldReserveDedupeKeyWithTtl() {
		// given
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET,
			2,
			AdminInventoryAction.ADD,
			" 보상 누락 "
		);
		given(redissonClient.<String>getBucket(anyString())).willReturn(bucket);
		given(bucket.setIfAbsent("1", Duration.ofSeconds(3))).willReturn(true);

		// when
		adminInventoryDedupeService.reserveOrThrow(11L, request);

		// then
		ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
		then(redissonClient).should().getBucket(keyCaptor.capture());
		assertThat(keyCaptor.getValue())
			.isEqualTo("admin:inventory:dedupe:11:MATCHING_TICKET:ADD:2:보상 누락");
		then(bucket).should().setIfAbsent("1", Duration.ofSeconds(3));
	}

	@Test
	@DisplayName("이미 예약된 동일 요청이면 중복 예외가 발생한다")
	void shouldThrowWhenDuplicateKeyAlreadyExists() {
		// given
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.OPTION_TICKET,
			1,
			AdminInventoryAction.REMOVE,
			"오지급 회수"
		);
		given(redissonClient.<String>getBucket(anyString())).willReturn(bucket);
		given(bucket.setIfAbsent("1", Duration.ofSeconds(3))).willReturn(false);

		// when & then
		assertThatThrownBy(() -> adminInventoryDedupeService.reserveOrThrow(11L, request))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ItemErrorCode.DUPLICATE_ADMIN_INVENTORY_ADJUSTMENT);
	}
}
