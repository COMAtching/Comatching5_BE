package com.comatching.user.domain.member.dto;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.comatching.common.domain.enums.ContactFrequency;
import com.fasterxml.jackson.databind.json.JsonMapper;

@DisplayName("ProfileUpdateRequest 테스트")
class ProfileUpdateRequestTest {

	private final JsonMapper objectMapper = JsonMapper.builder().build();

	@Test
	@DisplayName("한글 연락 빈도 값을 수정 요청으로 받을 수 있다")
	void shouldDeserializeKoreanContactFrequency() throws Exception {
		ProfileUpdateRequest request = objectMapper.readValue(
			"{\"contactFrequency\":\"자주\"}",
			ProfileUpdateRequest.class
		);

		assertThat(request.contactFrequency()).isEqualTo(ContactFrequency.FREQUENT);
	}

	@Test
	@DisplayName("profileImageKey를 profileImageUrl 별칭으로 받을 수 있다")
	void shouldDeserializeProfileImageKeyAlias() throws Exception {
		ProfileUpdateRequest request = objectMapper.readValue(
			"{\"profileImageKey\":\"default_cat\"}",
			ProfileUpdateRequest.class
		);

		assertThat(request.profileImageUrl()).isEqualTo("default_cat");
	}
}
