package com.comatching.gateway.config;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class GatewayRouteConfigurationTest {

	@ParameterizedTest
	@ValueSource(strings = {"application-local.yml", "application-aws.yml"})
	void shouldNotExposeInternalApiRoutes(String resourceName) throws Exception {
		GatewayProperties gateway = load(resourceName)
			.bind("spring.cloud.gateway.server.webflux", Bindable.of(GatewayProperties.class))
			.orElseThrow(() -> new IllegalStateException("Gateway configuration missing"));
		assertThat(gateway.getRoutes()).isNotEmpty();
		assertThat(gateway.getRoutes()).allSatisfy(route ->
			assertThat(route.getPredicates()).allSatisfy(predicate ->
				assertThat(predicate.getArgs().values()).noneMatch(value -> value.contains("/api/internal/"))));
	}

	@ParameterizedTest
	@ValueSource(strings = {"application-local.yml", "application-aws.yml"})
	void shouldRouteReissueAsPublicAndAllowPatchCors(String resourceName) throws Exception {
		Binder binder = load(resourceName);
		GatewayProperties gateway = binder
			.bind("spring.cloud.gateway.server.webflux", Bindable.of(GatewayProperties.class))
			.orElseThrow(() -> new IllegalStateException("Gateway configuration missing"));
		var publicRoute = gateway.getRoutes().stream()
			.filter(route -> route.getId().equals("user-service-public")).findFirst().orElseThrow();
		assertThat(publicRoute.getPredicates()).anySatisfy(predicate ->
			assertThat(predicate.getArgs().values()).contains("/api/auth/reissue"));
		assertThat(publicRoute.getFilters()).noneMatch(filter -> filter.getName().equals("AuthorizationHeaderFilter"));

		GlobalCorsProperties cors = binder
			.bind("spring.cloud.gateway.server.webflux.globalcors", Bindable.of(GlobalCorsProperties.class))
			.orElseThrow(() -> new IllegalStateException("CORS configuration missing"));
		assertThat(cors.getCorsConfigurations()).containsKey("/**");
		assertThat(cors.getCorsConfigurations().get("/**").getAllowedMethods()).contains("PATCH");
	}

	private Binder load(String resourceName) throws Exception {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader()
			.load(resourceName, new ClassPathResource(resourceName));
		return new Binder(ConfigurationPropertySources.from(sources));
	}
}
