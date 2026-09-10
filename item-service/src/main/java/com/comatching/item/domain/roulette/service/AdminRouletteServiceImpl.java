package com.comatching.item.domain.roulette.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.dto.member.AdminGiftCardUserProfileDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.item.domain.roulette.dto.response.AdminGiftCardWinnerResponse;
import com.comatching.item.domain.roulette.entity.RouletteHistory;
import com.comatching.item.domain.roulette.enums.RewardType;
import com.comatching.item.domain.roulette.repository.RouletteHistoryRepository;
import com.comatching.item.global.exception.ItemErrorCode;
import com.comatching.item.infra.client.UserAdminClient;

import feign.FeignException;
import feign.codec.DecodeException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminRouletteServiceImpl implements AdminRouletteService {

    private final RouletteHistoryRepository rouletteHistoryRepository;
    private final UserAdminClient userAdminClient;

    @Override
    @Transactional(readOnly = true)
    public List<AdminGiftCardWinnerResponse> getUnpaidGiftCardWinners() {
        // rewardType이 GIFT_CARD이고 rewardGranted가 false인 이력만 최신순으로 가져온다.
        List<RouletteHistory> histories = rouletteHistoryRepository
            .findAllByReward_RewardTypeAndRewardGrantedFalseOrderByParticipatedAtDesc(RewardType.GIFT_CARD);

        // 미지급 당첨자가 없으면 user-service를 호출하지 않고 빈 명단을 반환한다.
        if (histories.isEmpty()) { return List.of(); }

        // user-service에서 받은 회원 목록을 memberId로 찾을 수 있게 만들어 각 당첨 이력에 회원 정보를 붙인다.
        Map<Long, AdminGiftCardUserProfileDto> usersById = getUsersByMemberId(histories);

        // 가져온 유저 정보 당첨 정보와 매핑
        return histories.stream()
            .map(history -> AdminGiftCardWinnerResponse.from(
                history,
                getUserOrThrow(usersById, history.getMemberId())
            ))
            .toList();
    }

    @Override
    @Transactional
    public void markGiftCardAsGranted(Long historyId) {
        // 같은 당첨 이력에 대한 동시 요청을 직렬화해 지급 완료 상태가 중복 처리되지 않게 한다.
        RouletteHistory history = rouletteHistoryRepository.findByIdWithRewardForUpdate(historyId)
            .orElseThrow(() -> new BusinessException(GeneralErrorCode.NOT_FOUND));

        // 예외처리
        if (history.getReward().getRewardType() != RewardType.GIFT_CARD) {
            // 일반 아이템, 풀세트, 꽝 이력은 상품권 지급 완료 대상으로 사용할 수 없다.
            throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE);
        }

        // 미지급 상태일경우 지급처리 완료
        if (!history.isRewardGranted()) {
            history.markRewardAsGranted();
        }
    }

    private Map<Long, AdminGiftCardUserProfileDto> getUsersByMemberId(List<RouletteHistory> histories) {
        // 같은 회원이 여러 상품권에 당첨됐어도 user-service에는 회원 ID를 한 번만 전달한다.
        List<Long> memberIds = histories.stream()
            .map(RouletteHistory::getMemberId)
            .distinct()
            .toList();

        try {
            // 당첨자별 단건 호출을 피하고 현재 미지급 명단의 회원 정보를 한 번에 조회한다.
            return userAdminClient.getUsersByIds(memberIds).stream()
                .collect(Collectors.toMap(AdminGiftCardUserProfileDto::id, Function.identity()));
        } catch (DecodeException ignored) {
            throw new BusinessException(ItemErrorCode.USER_QUERY_FAILED);
        } catch (FeignException ignored) {
            throw new BusinessException(ItemErrorCode.USER_QUERY_FAILED);
        }
    }

    // user-service 배치 응답에서 당첨 이력의 memberId와 일치하는 회원 정보를 반환한다.
    // 활성 회원 정보가 누락된 경우 정확한 지급 대상 명단을 만들 수 없으므로 ITEM-004 예외를 발생시킨다.
    private AdminGiftCardUserProfileDto getUserOrThrow(
        Map<Long, AdminGiftCardUserProfileDto> usersById,
        Long memberId
    ) {
        AdminGiftCardUserProfileDto user = usersById.get(memberId);
        if (user == null) {
            throw new BusinessException(ItemErrorCode.TARGET_USER_NOT_FOUND);
        }
        return user;
    }

}
