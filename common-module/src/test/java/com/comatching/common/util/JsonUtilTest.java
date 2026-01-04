package com.comatching.common.util;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

class JsonUtilTest {

	@Test
	void toJson_Success() {
		// given
		TestDto dto = new TestDto("test", 20, LocalDateTime.now());

		// when
		String json = JsonUtil.toJson(dto);

		// then
		assertThat(json).contains("test");
		assertThat(json).contains("\"age\":20");
	}

	@Test
	void fromJson_Success() {
		// given
		String json = "{\"name\":\"test\",\"age\":20}";

		// when
		TestDto result = JsonUtil.fromJson(json, TestDto.class);

		// then
		assertThat(result.name).isEqualTo("test");
		assertThat(result.age).isEqualTo(20);
	}

	@Test
	void fromJson_Fail() {
		// given
		String invalidJson = "{name:test, age:20";

		// when & then
		assertThatThrownBy(() -> JsonUtil.fromJson(invalidJson, TestDto.class))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(GeneralErrorCode.JSON_PARSE_ERROR);
	}

	static class TestDto {
		public String name;
		public int age;
		public LocalDateTime createdAt;

		public TestDto() {
		}

		public TestDto(String name, int age, LocalDateTime createdAt) {
			this.name = name;
			this.age = age;
			this.createdAt = createdAt;
		}
	}

}