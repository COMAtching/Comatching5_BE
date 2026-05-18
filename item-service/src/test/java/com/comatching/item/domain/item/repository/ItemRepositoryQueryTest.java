package com.comatching.item.domain.item.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.comatching.common.domain.enums.ItemType;

@DisplayName("ItemRepository 쿼리 계약 테스트")
class ItemRepositoryQueryTest {

	@Test
	@DisplayName("아이템 사용 대상 조회는 유효기간 조건 없이 quantity > 0만 사용한다")
	void shouldFindUsableItemsWithoutExpirationCondition() throws NoSuchMethodException {
		// when
		String query = queryOf("findAllUsableItems", Long.class, ItemType.class);

		// then
		assertThat(query).contains("i.quantity > 0");
		assertThat(query).doesNotContain("expiredAt > CURRENT_TIMESTAMP");
	}

	@Test
	@DisplayName("내 아이템 조회는 유효기간 조건 없이 quantity > 0만 사용한다")
	void shouldFindMyUsableItemsWithoutExpirationCondition() throws NoSuchMethodException {
		// when
		String query = queryOf("findMyUsableItems", Long.class, ItemType.class, Pageable.class);

		// then
		assertThat(query).contains("i.quantity > 0");
		assertThat(query).doesNotContain("expiredAt > CURRENT_TIMESTAMP");
	}

	@Test
	@DisplayName("타입별 수량 집계는 유효기간 조건 없이 quantity > 0만 사용한다")
	void shouldSumUsableQuantityWithoutExpirationCondition() throws NoSuchMethodException {
		// when
		String query = queryOf("sumUsableQuantityByMemberIdAndItemType", Long.class, ItemType.class);

		// then
		assertThat(query).contains("SUM(i.quantity)");
		assertThat(query).contains("i.quantity > 0");
		assertThat(query).doesNotContain("expiredAt > CURRENT_TIMESTAMP");
	}

	@Test
	@DisplayName("관리자 목록용 일괄 집계는 memberId와 itemType으로 그룹화한다")
	void shouldAggregateQuantitiesByMemberIdAndItemType() throws NoSuchMethodException {
		// when
		String query = queryOf("sumUsableQuantityByMemberIds", List.class);

		// then
		assertThat(query).contains("GROUP BY i.memberId, i.itemType");
		assertThat(query).doesNotContain("expiredAt > CURRENT_TIMESTAMP");
	}

	private static String queryOf(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
		Method method = ItemRepository.class.getMethod(methodName, parameterTypes);
		return method.getAnnotation(Query.class).value();
	}
}
