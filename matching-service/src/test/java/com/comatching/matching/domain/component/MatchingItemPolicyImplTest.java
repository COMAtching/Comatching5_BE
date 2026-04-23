package com.comatching.matching.domain.component;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.item.ItemConsumption;
import com.comatching.matching.domain.dto.MatchingRequest;

class MatchingItemPolicyImplTest {

	private final MatchingItemPolicyImpl policy = new MatchingItemPolicyImpl();

	@Test
	@DisplayName("나이 제한 옵션을 사용하면 옵션 티켓이 1개 추가 차감된다")
	void shouldChargeExtraOptionTicketWhenAgeLimitIsUsed() {
		MatchingRequest request = new MatchingRequest(
			null, null, null, null, false, null,
			-2, 3
		);

		List<ItemConsumption> consumptions = policy.determine(request);

		assertThat(consumptions).containsExactly(
			new ItemConsumption(ItemType.MATCHING_TICKET, 1),
			new ItemConsumption(ItemType.OPTION_TICKET, 1)
		);
	}
}
