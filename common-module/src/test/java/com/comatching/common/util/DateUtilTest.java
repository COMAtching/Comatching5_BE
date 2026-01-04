package com.comatching.common.util;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class DateUtilTest {

	@Test
	void toString_Success() {
		// given
		LocalDateTime dateTime = LocalDateTime.of(2026, 1, 1, 12, 30, 45);

		// when
		String result = DateUtil.toString(dateTime);

		// then
		assertThat(result).isEqualTo("2026-01-01 12:30:45");
	}

	@Test
	void toString_Null() {
		// when
		String result = DateUtil.toString(null);

		// then
		assertThat(result).isEqualTo("");
	}

	@Test
	void toLocalDateTime_Success() {
		// given
		String dateStr = "2026-12-25 10:00:00";

		// when
		LocalDateTime result = DateUtil.toLocalDateTime(dateStr);

		// then
		assertThat(result.getYear()).isEqualTo(2026);
		assertThat(result.getMonthValue()).isEqualTo(12);
		assertThat(result.getDayOfMonth()).isEqualTo(25);
	}
}