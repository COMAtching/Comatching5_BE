package com.comatching.item.domain.item.dto;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.item.entity.ItemHistory;
import com.comatching.item.domain.item.enums.ItemHistoryType;

@DisplayName("ItemHistoryResponse 테스트")
class ItemHistoryResponseTest {

	@Test
	@DisplayName("저장된 설명 문구와 관계없이 description은 아이템명으로 반환한다")
	void shouldReturnItemNameAsDescription() {
		// given
		ItemHistory history = ItemHistory.builder()
			.memberId(1L)
			.itemType(ItemType.MATCHING_TICKET)
			.historyType(ItemHistoryType.CHARGE)
			.quantity(3)
			.description("기본 옵션만으로 매칭을 할 수 있는 아이템 충전")
			.build();

		// when
		ItemHistoryResponse response = ItemHistoryResponse.from(history);

		// then
		assertThat(response.description()).isEqualTo("매칭권");
	}
}
