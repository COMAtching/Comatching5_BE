package com.comatching.item.infra.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.item.domain.roulette.dto.response.AdminGiftCardWinnerResponse;
import com.comatching.item.domain.roulette.service.AdminRouletteService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Admin Roulette API", description = "관리자 전용 룰렛 상품권 관리")
@RestController
@RequestMapping("/api/v1/admin/roulette")
@RequiredArgsConstructor
public class AdminRouletteController {

    private final AdminRouletteService adminRouletteService;

    @RequireRole(MemberRole.ROLE_ADMIN)
    @Operation(summary = "미지급 상품권 당첨자 조회", description = "상품권에 당첨됐지만 아직 지급되지 않은 이력을 최신순으로 조회합니다.")
    @GetMapping("/gift-cards/unpaid")
    public ResponseEntity<ApiResponse<List<AdminGiftCardWinnerResponse>>> getUnpaidGiftCardWinners(
        @CurrentMember MemberInfo memberInfo
    ) {
        return ResponseEntity.ok(ApiResponse.ok(adminRouletteService.getUnpaidGiftCardWinners()));
    }

    @RequireRole(MemberRole.ROLE_ADMIN)
    @Operation(summary = "상품권 지급 완료 처리", description = "상품권 지급을 완료한 당첨 이력을 지급 완료 상태로 변경합니다.")
    @PatchMapping("/gift-cards/{historyId}/grant")
    public ResponseEntity<ApiResponse<Void>> markGiftCardAsGranted(
        @CurrentMember MemberInfo memberInfo,
        @PathVariable Long historyId
    ) {
        adminRouletteService.markGiftCardAsGranted(historyId);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
