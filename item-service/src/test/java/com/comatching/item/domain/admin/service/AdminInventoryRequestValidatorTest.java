package com.comatching.item.domain.admin.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.admin.dto.AdminInventoryAction;
import com.comatching.item.domain.admin.dto.AdminInventoryUpdateRequest;

@DisplayName("AdminInventoryRequestValidator 테스트")
class AdminInventoryRequestValidatorTest {

	private final AdminInventoryRequestValidator validator = new AdminInventoryRequestValidator();

	@Test
	@DisplayName("유효한 요청은 예외를 던지지 않는다")
	void shouldPassWhenRequestIsValid() {
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET, 1, AdminInventoryAction.ADD, "보상 지급"
		);

		assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("수량이 0 이하이면 예외가 발생한다")
	void shouldThrowWhenQuantityIsNotPositive() {
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET, 0, AdminInventoryAction.ADD, "보상 지급"
		);

		assertThatThrownBy(() -> validator.validate(request)).isInstanceOf(BusinessException.class);
	}

	@Test
	@DisplayName("사유가 공백이면 예외가 발생한다")
	void shouldThrowWhenReasonIsBlank() {
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET, 1, AdminInventoryAction.ADD, "  "
		);

		assertThatThrownBy(() -> validator.validate(request)).isInstanceOf(BusinessException.class);
	}

	@Test
	@DisplayName("사유가 255자를 초과하면 예외가 발생한다")
	void shouldThrowWhenReasonExceedsMaxLength() {
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET, 1, AdminInventoryAction.ADD, "가".repeat(256)
		);

		assertThatThrownBy(() -> validator.validate(request)).isInstanceOf(BusinessException.class);
	}
}
