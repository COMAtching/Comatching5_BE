package com.comatching.item.domain.roulette.service;

import com.comatching.common.dto.member.MemberInfo;
import com.comatching.item.domain.roulette.dto.RouletteSpinResponse;
import com.comatching.item.domain.roulette.enums.RouletteType;

public interface RouletteService {
    RouletteSpinResponse spinRoulette(MemberInfo memberInfo, RouletteType rouletteType);
}
