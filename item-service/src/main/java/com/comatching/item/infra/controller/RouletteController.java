package com.comatching.item.infra.controller;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.item.domain.roulette.dto.RouletteSpinResponse;
import com.comatching.item.domain.roulette.enums.RouletteType;
import com.comatching.item.domain.roulette.service.RouletteService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
@RequestMapping("/api/roulette")
public class RouletteController {
    private final RouletteService rouletteService;

    @PostMapping("/spins")
    public ResponseEntity<ApiResponse<RouletteSpinResponse>> spinRoulette(
            @CurrentMember MemberInfo memberInfo,
            @RequestParam RouletteType rouletteType
    ) {
        RouletteSpinResponse response = rouletteService.spinRoulette(memberInfo, rouletteType);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
