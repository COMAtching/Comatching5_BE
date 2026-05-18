package com.comatching.item.domain.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.comatching.common.domain.enums.ItemType;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

@DisplayName("AdminInventoryUpdateRequest 검증 테스트")
class AdminInventoryUpdateRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	@DisplayName("관리자 아이템 조정 사유는 필수다")
	void shouldRequireReason() {
		// given
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET,
			1,
			AdminInventoryAction.ADD,
			" "
		);

		// when
		Set<ConstraintViolation<AdminInventoryUpdateRequest>> violations = validator.validate(request);

		// then
		assertThat(violations).extracting(ConstraintViolation::getMessage)
			.contains("수정 사유는 필수입니다.");
	}

	@Test
	@DisplayName("관리자 아이템 조정 사유는 255자 이하여야 한다")
	void shouldLimitReasonLength() {
		// given
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET,
			1,
			AdminInventoryAction.ADD,
			"a".repeat(256)
		);

		// when
		Set<ConstraintViolation<AdminInventoryUpdateRequest>> violations = validator.validate(request);

		// then
		assertThat(violations).extracting(ConstraintViolation::getMessage)
			.contains("수정 사유는 255자 이하여야 합니다.");
	}
}
