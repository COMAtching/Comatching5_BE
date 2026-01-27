package com.comatching.chat.domain.service.block;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.comatching.chat.domain.dto.BlockedUserResponse;
import com.comatching.chat.domain.entity.UserBlock;
import com.comatching.chat.domain.repository.UserBlockRepository;
import com.comatching.chat.global.exception.ChatErrorCode;
import com.comatching.common.exception.BusinessException;

@ExtendWith(MockitoExtension.class)
class BlockServiceImplTest {

	@Mock
	private UserBlockRepository userBlockRepository;

	@Mock
	private RedisTemplate<String, Object> redisTemplate;

	@Mock
	private ValueOperations<String, Object> valueOperations;

	@InjectMocks
	private BlockServiceImpl blockService;

	private static final Long BLOCKER_USER_ID = 1L;
	private static final Long BLOCKED_USER_ID = 2L;

	@BeforeEach
	void setUp() {
		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
	}

	@Nested
	@DisplayName("blockUser 메서드")
	class BlockUserTest {

		@Test
		@DisplayName("정상적으로 사용자를 차단한다")
		void blockUser_success() {
			// given
			given(userBlockRepository.existsByBlockerUserIdAndBlockedUserId(BLOCKER_USER_ID, BLOCKED_USER_ID))
				.willReturn(false);
			given(userBlockRepository.save(any(UserBlock.class)))
				.willAnswer(invocation -> invocation.getArgument(0));

			// when
			blockService.blockUser(BLOCKER_USER_ID, BLOCKED_USER_ID);

			// then
			then(userBlockRepository).should().save(any(UserBlock.class));
			then(redisTemplate).should().delete("user:block:" + BLOCKER_USER_ID);
		}

		@Test
		@DisplayName("자기 자신을 차단하면 예외가 발생한다")
		void blockUser_cannotBlockSelf() {
			// when & then
			assertThatThrownBy(() -> blockService.blockUser(BLOCKER_USER_ID, BLOCKER_USER_ID))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> {
					BusinessException be = (BusinessException) e;
					assertThat(be.getErrorCode()).isEqualTo(ChatErrorCode.CANNOT_BLOCK_SELF);
				});
		}

		@Test
		@DisplayName("이미 차단한 사용자를 다시 차단하면 예외가 발생한다")
		void blockUser_alreadyBlocked() {
			// given
			given(userBlockRepository.existsByBlockerUserIdAndBlockedUserId(BLOCKER_USER_ID, BLOCKED_USER_ID))
				.willReturn(true);

			// when & then
			assertThatThrownBy(() -> blockService.blockUser(BLOCKER_USER_ID, BLOCKED_USER_ID))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> {
					BusinessException be = (BusinessException) e;
					assertThat(be.getErrorCode()).isEqualTo(ChatErrorCode.ALREADY_BLOCKED);
				});
		}
	}

	@Nested
	@DisplayName("unblockUser 메서드")
	class UnblockUserTest {

		@Test
		@DisplayName("정상적으로 차단을 해제한다")
		void unblockUser_success() {
			// given
			given(userBlockRepository.existsByBlockerUserIdAndBlockedUserId(BLOCKER_USER_ID, BLOCKED_USER_ID))
				.willReturn(true);

			// when
			blockService.unblockUser(BLOCKER_USER_ID, BLOCKED_USER_ID);

			// then
			then(userBlockRepository).should().deleteByBlockerUserIdAndBlockedUserId(BLOCKER_USER_ID, BLOCKED_USER_ID);
			then(redisTemplate).should().delete("user:block:" + BLOCKER_USER_ID);
		}

		@Test
		@DisplayName("차단하지 않은 사용자를 해제하면 예외가 발생한다")
		void unblockUser_notBlocked() {
			// given
			given(userBlockRepository.existsByBlockerUserIdAndBlockedUserId(BLOCKER_USER_ID, BLOCKED_USER_ID))
				.willReturn(false);

			// when & then
			assertThatThrownBy(() -> blockService.unblockUser(BLOCKER_USER_ID, BLOCKED_USER_ID))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> {
					BusinessException be = (BusinessException) e;
					assertThat(be.getErrorCode()).isEqualTo(ChatErrorCode.NOT_BLOCKED);
				});
		}
	}

	@Nested
	@DisplayName("isBlocked 메서드")
	class IsBlockedTest {

		@Test
		@DisplayName("차단된 사용자면 true를 반환한다")
		void isBlocked_returnsTrue() {
			// given
			given(valueOperations.get("user:block:" + BLOCKER_USER_ID)).willReturn(null);
			UserBlock userBlock = UserBlock.builder()
				.blockerUserId(BLOCKER_USER_ID)
				.blockedUserId(BLOCKED_USER_ID)
				.build();
			given(userBlockRepository.findByBlockerUserId(BLOCKER_USER_ID))
				.willReturn(List.of(userBlock));

			// when
			boolean result = blockService.isBlocked(BLOCKER_USER_ID, BLOCKED_USER_ID);

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("차단되지 않은 사용자면 false를 반환한다")
		void isBlocked_returnsFalse() {
			// given
			given(valueOperations.get("user:block:" + BLOCKER_USER_ID)).willReturn(null);
			given(userBlockRepository.findByBlockerUserId(BLOCKER_USER_ID))
				.willReturn(List.of());

			// when
			boolean result = blockService.isBlocked(BLOCKER_USER_ID, BLOCKED_USER_ID);

			// then
			assertThat(result).isFalse();
		}

		@Test
		@DisplayName("캐시에서 차단 정보를 조회한다")
		void isBlocked_fromCache() {
			// given
			Set<Long> cachedBlockedIds = Set.of(BLOCKED_USER_ID);
			given(valueOperations.get("user:block:" + BLOCKER_USER_ID)).willReturn(cachedBlockedIds);

			// when
			boolean result = blockService.isBlocked(BLOCKER_USER_ID, BLOCKED_USER_ID);

			// then
			assertThat(result).isTrue();
			then(userBlockRepository).should(never()).findByBlockerUserId(any());
		}
	}

	@Nested
	@DisplayName("getBlockedUsers 메서드")
	class GetBlockedUsersTest {

		@Test
		@DisplayName("차단한 사용자 목록을 반환한다")
		void getBlockedUsers_success() {
			// given
			UserBlock userBlock = UserBlock.builder()
				.blockerUserId(BLOCKER_USER_ID)
				.blockedUserId(BLOCKED_USER_ID)
				.build();
			given(userBlockRepository.findByBlockerUserId(BLOCKER_USER_ID))
				.willReturn(List.of(userBlock));

			// when
			List<BlockedUserResponse> result = blockService.getBlockedUsers(BLOCKER_USER_ID);

			// then
			assertThat(result).hasSize(1);
			assertThat(result.get(0).userId()).isEqualTo(BLOCKED_USER_ID);
		}

		@Test
		@DisplayName("차단한 사용자가 없으면 빈 목록을 반환한다")
		void getBlockedUsers_emptyList() {
			// given
			given(userBlockRepository.findByBlockerUserId(BLOCKER_USER_ID))
				.willReturn(List.of());

			// when
			List<BlockedUserResponse> result = blockService.getBlockedUsers(BLOCKER_USER_ID);

			// then
			assertThat(result).isEmpty();
		}
	}

	@Nested
	@DisplayName("getBlockedUserIds 메서드")
	class GetBlockedUserIdsTest {

		@Test
		@DisplayName("캐시 미스 시 DB에서 조회하고 캐시에 저장한다")
		void getBlockedUserIds_cacheMiss() {
			// given
			given(valueOperations.get("user:block:" + BLOCKER_USER_ID)).willReturn(null);
			UserBlock userBlock = UserBlock.builder()
				.blockerUserId(BLOCKER_USER_ID)
				.blockedUserId(BLOCKED_USER_ID)
				.build();
			given(userBlockRepository.findByBlockerUserId(BLOCKER_USER_ID))
				.willReturn(List.of(userBlock));

			// when
			Set<Long> result = blockService.getBlockedUserIds(BLOCKER_USER_ID);

			// then
			assertThat(result).containsExactly(BLOCKED_USER_ID);
			then(valueOperations).should().set(
				eq("user:block:" + BLOCKER_USER_ID),
				anySet(),
				eq(Duration.ofMinutes(30))
			);
		}

		@Test
		@DisplayName("캐시 히트 시 캐시에서 조회한다")
		void getBlockedUserIds_cacheHit() {
			// given
			Set<Long> cachedBlockedIds = Set.of(BLOCKED_USER_ID, 3L);
			given(valueOperations.get("user:block:" + BLOCKER_USER_ID)).willReturn(cachedBlockedIds);

			// when
			Set<Long> result = blockService.getBlockedUserIds(BLOCKER_USER_ID);

			// then
			assertThat(result).containsExactlyInAnyOrder(BLOCKED_USER_ID, 3L);
			then(userBlockRepository).should(never()).findByBlockerUserId(any());
		}
	}
}
