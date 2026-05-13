package com.comatching.common.domain.enums;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.json.JsonMapper;

@DisplayName("ContactFrequency Enum 테스트")
class ContactFrequencyTest {

	private final JsonMapper objectMapper = JsonMapper.builder().build();

	@Test
	@DisplayName("영문 enum 이름으로 역직렬화할 수 있다")
	void shouldDeserializeFromEnumName() throws Exception {
		ContactFrequency result = objectMapper.readValue("\"FREQUENT\"", ContactFrequency.class);

		assertThat(result).isEqualTo(ContactFrequency.FREQUENT);
	}

	@Test
	@DisplayName("한글 코드로 역직렬화할 수 있다")
	void shouldDeserializeFromKoreanCode() throws Exception {
		ContactFrequency result = objectMapper.readValue("\"자주\"", ContactFrequency.class);

		assertThat(result).isEqualTo(ContactFrequency.FREQUENT);
	}

	@Test
	@DisplayName("한글 설명으로 역직렬화할 수 있다")
	void shouldDeserializeFromKoreanDescription() throws Exception {
		ContactFrequency result = objectMapper.readValue("\"보통 연락\"", ContactFrequency.class);

		assertThat(result).isEqualTo(ContactFrequency.NORMAL);
	}
}
