package com.comatching.chat.domain.service.block;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import com.comatching.chat.domain.entity.UserBlock;
import com.comatching.chat.domain.dto.BlockedUserResponse;
import com.comatching.chat.domain.repository.UserBlockRepository;
import com.comatching.chat.global.exception.ChatErrorCode;
import com.comatching.common.exception.BusinessException;

@ExtendWith(MockitoExtension.class)
class BlockServiceImplTest {
	private static final Long BLOCKER_USER_ID = 1L;
	private static final Long BLOCKED_USER_ID = 2L;
	@Mock UserBlockRepository userBlockRepository;
	@Mock org.springframework.context.ApplicationEventPublisher events;
	@InjectMocks BlockServiceImpl blockService;

	@Test
	void blockAndUnblockValidateTransitions() {
		assertThatThrownBy(() -> blockService.blockUser(1L, 1L)).isInstanceOf(BusinessException.class);
		blockService.blockUser(1L, 2L);
		then(userBlockRepository).should().insert(any(UserBlock.class));
		given(userBlockRepository.insert(any(UserBlock.class))).willThrow(new DuplicateKeyException("pair"));
		assertThatThrownBy(() -> blockService.blockUser(1L, 2L)).extracting("errorCode").isEqualTo(ChatErrorCode.ALREADY_BLOCKED);
		assertThatThrownBy(() -> blockService.unblockUser(1L, 2L)).extracting("errorCode").isEqualTo(ChatErrorCode.NOT_BLOCKED);
		given(userBlockRepository.release(1L, 2L)).willReturn(1L);
		blockService.unblockUser(1L, 2L);
	}

	@Test
	void oldBlockDocumentIsActiveAndOnlyReleasedRowsDefineHistoryBoundary() {
		UserBlock row = new UserBlock(1L, 2L);
		given(userBlockRepository.findByBlockerUserIdAndBlockedUserId(1L, 2L)).willReturn(Optional.of(row));
		assertThat(blockService.isBlocked(1L, 2L)).isTrue();
		assertThatThrownBy(() -> blockService.visibleAfter(1L, 2L)).extracting("errorCode").isEqualTo(ChatErrorCode.NOT_EXIST_CHATROOM);
		var cutoff = LocalDateTime.now();
		ReflectionTestUtils.setField(row, "unblockedAt", cutoff);
		assertThat(blockService.isBlocked(1L, 2L)).isFalse();
		assertThat(blockService.visibleAfter(1L, 2L)).isEqualTo(cutoff);
		assertThat(blockService.hidesMessage(1L, 2L, cutoff)).isTrue();
		assertThat(blockService.hidesMessage(1L, 2L, cutoff.plusSeconds(1))).isFalse();
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

}
