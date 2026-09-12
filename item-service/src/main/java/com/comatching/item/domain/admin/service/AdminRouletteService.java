package com.comatching.item.domain.admin.service;

import java.util.List;

import com.comatching.item.domain.roulette.dto.response.AdminGiftCardWinnerResponse;

public interface AdminRouletteService {

    /** 아직 지급되지 않은 상품권 당첨자와 관리자 확인용 회원 정보를 반환한다. */
    List<AdminGiftCardWinnerResponse> getUnpaidGiftCardWinners();

    /** 관리자가 실제 상품권 지급을 끝낸 당첨 이력을 지급 완료 상태로 변경한다. */
    void markGiftCardAsGranted(Long historyId);
}
