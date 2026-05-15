package com.comatching.gateway.config;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class GatewayRouteConfigurationTest {

	@Test
	void shouldNotExposeInternalApiRoutes() throws Exception {
		assertThat(read("application.yml")).doesNotContain("/api/internal/");
		assertThat(read("application-aws.yml")).doesNotContain("/api/internal/");
	}

	@Test
	void shouldRouteReissueAsPublicAndAllowPatchCors() throws Exception {
		assertThat(read("application.yml"))
			.contains("/api/auth/reissue")
			.contains("allowedMethods: [GET, POST, PUT, PATCH, DELETE, OPTIONS]");
		assertThat(read("application-aws.yml"))
			.contains("/api/auth/reissue")
			.contains("allowedMethods: [GET, POST, PUT, PATCH, DELETE, OPTIONS]");
	}

	private String read(String resourceName) throws Exception {
		return new String(new ClassPathResource(resourceName).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
	}
}
