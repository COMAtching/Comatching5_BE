package com.comatching.item.domain.roulette.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;

import com.comatching.item.domain.admin.service.AdminRouletteServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.comatching.common.dto.member.AdminGiftCardUserProfileDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.item.domain.roulette.dto.response.AdminGiftCardWinnerResponse;
import com.comatching.item.domain.roulette.entity.RouletteHistory;
import com.comatching.item.domain.roulette.entity.RouletteReward;
import com.comatching.item.domain.roulette.enums.RewardType;
import com.comatching.item.domain.roulette.enums.RouletteType;
import com.comatching.item.domain.roulette.repository.RouletteHistoryRepository;
import com.comatching.item.global.exception.ItemErrorCode;
import com.comatching.item.infra.client.UserAdminClient;

import feign.FeignException;
import feign.codec.DecodeException;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminRouletteServiceImpl 테스트")
class AdminRouletteServiceImplTest {

    @Mock
    private RouletteHistoryRepository rouletteHistoryRepository;

    @Mock
    private UserAdminClient userAdminClient;

    @InjectMocks
    private AdminRouletteServiceImpl adminRouletteService;

    @Test
    @DisplayName("미지급 상품권 이력이 없으면 회원 조회 없이 빈 목록을 반환한다")
    void shouldReturnEmptyListWithoutRequestingUsers() {
        given(rouletteHistoryRepository
            .findAllByReward_RewardTypeAndRewardGrantedFalseOrderByParticipatedAtDesc(RewardType.GIFT_CARD))
            .willReturn(List.of());

        List<AdminGiftCardWinnerResponse> responses = adminRouletteService.getUnpaidGiftCardWinners();

        assertThat(responses).isEmpty();
        then(userAdminClient).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("같은 회원의 여러 당첨 이력은 회원을 한 번만 조회하고 당첨 건별로 반환한다")
    void shouldRequestDistinctUsersAndReturnEveryWinningHistory() {
        RouletteHistory newestHistory = history(101L, 1L, "2만원권 상품권", false);
        RouletteHistory sameMemberHistory = history(100L, 1L, "1만원권 상품권", false);
        RouletteHistory anotherMemberHistory = history(99L, 2L, "1만원권 상품권", false);
        given(rouletteHistoryRepository
            .findAllByReward_RewardTypeAndRewardGrantedFalseOrderByParticipatedAtDesc(RewardType.GIFT_CARD))
            .willReturn(List.of(newestHistory, sameMemberHistory, anotherMemberHistory));
        given(userAdminClient.getUsersByIds(List.of(1L, 2L))).willReturn(List.of(
            new AdminGiftCardUserProfileDto(2L, "second@example.com", "두번째", "두번"),
            new AdminGiftCardUserProfileDto(1L, "first@example.com", "첫번째", "첫번")
        ));

        List<AdminGiftCardWinnerResponse> responses = adminRouletteService.getUnpaidGiftCardWinners();

        then(userAdminClient).should().getUsersByIds(List.of(1L, 2L));
        assertThat(responses).hasSize(3);
        assertThat(responses).extracting(AdminGiftCardWinnerResponse::historyId)
            .containsExactly(101L, 100L, 99L);
        assertThat(responses).extracting(AdminGiftCardWinnerResponse::memberId)
            .containsExactly(1L, 1L, 2L);
        assertThat(responses.get(0).email()).isEqualTo("first@example.com");
        assertThat(responses.get(0).realName()).isEqualTo("첫번째");
        assertThat(responses.get(0).nickname()).isEqualTo("첫번");
        assertThat(responses.get(0).rewardName()).isEqualTo("2만원권 상품권");
        assertThat(responses.get(0).rouletteType()).isEqualTo(RouletteType.SPECIAL);
        assertThat(responses.get(0).participatedAt()).isEqualTo(newestHistory.getParticipatedAt());
    }

    @Test
    @DisplayName("당첨 회원 정보가 응답에서 누락되면 해당 당첨 이력을 제외한다")
    void shouldSkipWinnerWhenProfileIsMissing() {
        RouletteHistory history = history(101L, 1L, "1만원권 상품권", false);
        given(rouletteHistoryRepository
            .findAllByReward_RewardTypeAndRewardGrantedFalseOrderByParticipatedAtDesc(RewardType.GIFT_CARD))
            .willReturn(List.of(history));
        given(userAdminClient.getUsersByIds(List.of(1L))).willReturn(List.of());

        List<AdminGiftCardWinnerResponse> responses = adminRouletteService.getUnpaidGiftCardWinners();

        assertThat(responses).isEmpty();
    }

    @Test
    @DisplayName("회원 응답 디코딩에 실패하면 사용자 조회 실패 예외로 변환한다")
    void shouldConvertDecodeException() {
        givenGiftCardHistory();
        given(userAdminClient.getUsersByIds(List.of(1L))).willThrow(mock(DecodeException.class));

        assertUserQueryFailed();
    }

    @Test
    @DisplayName("회원 서비스 호출에 실패하면 사용자 조회 실패 예외로 변환한다")
    void shouldConvertFeignException() {
        givenGiftCardHistory();
        given(userAdminClient.getUsersByIds(List.of(1L))).willThrow(mock(FeignException.class));

        assertUserQueryFailed();
    }

    @Test
    @DisplayName("미지급 상품권 이력을 지급 완료로 변경한다")
    void shouldMarkGiftCardAsGranted() {
        RouletteHistory history = history(101L, 1L, "1만원권 상품권", false);
        given(rouletteHistoryRepository.findByIdWithRewardForUpdate(101L))
            .willReturn(Optional.of(history));

        adminRouletteService.markGiftCardAsGranted(101L);

        assertThat(history.isRewardGranted()).isTrue();
    }

    @Test
    @DisplayName("이미 지급된 상품권 이력은 그대로 유지한다")
    void shouldKeepAlreadyGrantedGiftCardGranted() {
        RouletteHistory history = history(101L, 1L, "1만원권 상품권", true);
        given(rouletteHistoryRepository.findByIdWithRewardForUpdate(101L))
            .willReturn(Optional.of(history));

        adminRouletteService.markGiftCardAsGranted(101L);

        assertThat(history.isRewardGranted()).isTrue();
    }

    @Test
    @DisplayName("존재하지 않는 룰렛 이력이면 찾을 수 없음 예외가 발생한다")
    void shouldThrowWhenHistoryDoesNotExist() {
        given(rouletteHistoryRepository.findByIdWithRewardForUpdate(404L))
            .willReturn(Optional.empty());

        assertThatThrownBy(() -> adminRouletteService.markGiftCardAsGranted(404L))
            .isInstanceOf(BusinessException.class)
            .satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
                .isEqualTo(GeneralErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("상품권이 아닌 룰렛 이력은 지급 완료로 변경할 수 없다")
    void shouldRejectNonGiftCardHistory() {
        RouletteHistory history = history(
            101L,
            1L,
            "옵션권 1장",
            RewardType.OPTION_TICKET,
            true
        );
        given(rouletteHistoryRepository.findByIdWithRewardForUpdate(101L))
            .willReturn(Optional.of(history));

        assertThatThrownBy(() -> adminRouletteService.markGiftCardAsGranted(101L))
            .isInstanceOf(BusinessException.class)
            .satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
                .isEqualTo(GeneralErrorCode.INVALID_INPUT_VALUE));
    }

    private void givenGiftCardHistory() {
        given(rouletteHistoryRepository
            .findAllByReward_RewardTypeAndRewardGrantedFalseOrderByParticipatedAtDesc(RewardType.GIFT_CARD))
            .willReturn(List.of(history(101L, 1L, "1만원권 상품권", false)));
    }

    private void assertUserQueryFailed() {
        assertThatThrownBy(adminRouletteService::getUnpaidGiftCardWinners)
            .isInstanceOf(BusinessException.class)
            .satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
                .isEqualTo(ItemErrorCode.USER_QUERY_FAILED));
    }

    private RouletteHistory history(
        Long historyId,
        Long memberId,
        String rewardName,
        boolean rewardGranted
    ) {
        return history(historyId, memberId, rewardName, RewardType.GIFT_CARD, rewardGranted);
    }

    private RouletteHistory history(
        Long historyId,
        Long memberId,
        String rewardName,
        RewardType rewardType,
        boolean rewardGranted
    ) {
        RouletteReward reward = RouletteReward.builder()
            .rouletteType(RouletteType.SPECIAL)
            .rewardName(rewardName)
            .rewardType(rewardType)
            .quantity(rewardType == RewardType.GIFT_CARD ? 1 : 0)
            .rangeStart(1)
            .rangeEnd(10000)
            .remainingCount(rewardType == RewardType.GIFT_CARD ? 1 : null)
            .build();
        RouletteHistory history = RouletteHistory.builder()
            .memberId(memberId)
            .reward(reward)
            .rouletteType(RouletteType.SPECIAL)
            .rewardGranted(rewardGranted)
            .build();
        ReflectionTestUtils.setField(history, "id", historyId);
        return history;
    }
}
