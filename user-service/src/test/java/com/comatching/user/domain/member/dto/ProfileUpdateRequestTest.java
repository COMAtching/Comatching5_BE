package com.comatching.user.domain.member.dto;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

@DisplayName("ProfileUpdateRequest 테스트")
class ProfileUpdateRequestTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("소셜 연락처 필드가 JSON에 없으면 socialInfoProvided가 false다")
	void shouldMarkSocialInfoNotProvidedWhenSocialFieldsAreAbsent() throws Exception {
		// when
		ProfileUpdateRequest request = objectMapper.readValue("{}", ProfileUpdateRequest.class);

		// then
		assertThat(request.socialInfoProvided()).isFalse();
	}

	@Test
	@DisplayName("소셜 연락처 필드가 명시적 null이면 socialInfoProvided가 true다")
	void shouldMarkSocialInfoProvidedWhenSocialFieldsAreExplicitNull() throws Exception {
		// when
		ProfileUpdateRequest request = objectMapper.readValue(
			"{\"socialType\":null,\"socialAccountId\":null}",
			ProfileUpdateRequest.class
		);

		// then
		assertThat(request.socialInfoProvided()).isTrue();
		assertThat(request.socialType()).isNull();
		assertThat(request.socialAccountId()).isNull();
	}
}
