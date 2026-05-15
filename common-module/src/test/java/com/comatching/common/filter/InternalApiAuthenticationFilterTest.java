package com.comatching.common.filter;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class InternalApiAuthenticationFilterTest {

	private static final String INTERNAL_TOKEN = "test-internal-token";

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final InternalApiAuthenticationFilter filter = new InternalApiAuthenticationFilter(INTERNAL_TOKEN, objectMapper);

	@Test
	void shouldRejectInternalApiWithoutInternalToken() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/internal/users/1");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		JsonNode body = objectMapper.readTree(response.getContentAsString());
		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(body.get("code").asText()).isEqualTo("GEN-010");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void shouldAllowInternalApiWithValidInternalToken() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/internal/users/1");
		request.addHeader("X-Internal-Token", INTERNAL_TOKEN);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(chain.getRequest()).isSameAs(request);
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void shouldSkipPublicApi() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/items");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(chain.getRequest()).isSameAs(request);
	}
}
