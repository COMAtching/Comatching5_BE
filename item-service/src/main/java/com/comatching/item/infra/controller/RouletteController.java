package com.comatching.item.infra.controller;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.item.domain.roulette.dto.response.RoulettePageResponse;
import com.comatching.item.domain.roulette.dto.response.RouletteSpinResponse;
import com.comatching.item.domain.roulette.enums.RouletteType;
import com.comatching.item.domain.roulette.service.RouletteService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@AllArgsConstructor
@RequestMapping("/api/items/roulette")
public class RouletteController {
    private final RouletteService rouletteService;

    @PostMapping("/{rouletteType}/spins")
    @Operation(summary = "룰렛 돌리는 api", description = "무료 룰렛과 유료 룰렛 (1일 1회 제한)")
    public ResponseEntity<ApiResponse<RouletteSpinResponse>> spinRoulette(
            @CurrentMember MemberInfo memberInfo,
            @PathVariable RouletteType rouletteType
    ) {
        return ResponseEntity.ok(ApiResponse.ok(rouletteService.spinRoulette(memberInfo, rouletteType)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<RoulettePageResponse>> roulettePage(
            @CurrentMember MemberInfo memberInfo
    ) {
        return ResponseEntity.ok(ApiResponse.ok(rouletteService.roulettePage(memberInfo)));
    }
}
