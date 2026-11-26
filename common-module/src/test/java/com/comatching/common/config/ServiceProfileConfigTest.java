package com.comatching.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import com.comatching.common.config.kafka.KafkaEventRoute;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** 실제 ConfigData 로더로 환경변수의 프로파일 선택과 공통/환경 설정 병합을 검증한다. 외부 서버에 접속하지 않는다. */
class ServiceProfileConfigTest {

	private static final List<String> SERVICES = List.of(
		"user-service", "matching-service", "chat-service", "item-service", "notification", "gateway-service"
	);
	private static final String CALLBACK = "spring.security.oauth2.client.registration.kakao.redirect-uri";
	private static final String ROUTES = "spring.cloud.gateway.server.webflux.routes";

	@ParameterizedTest
	@ValueSource(strings = {"local", "aws"})
	void everyConsumerServiceDeclaresItsOwnDltRoutes(String profile) {
		Map<String, List<KafkaEventRoute>> expected = Map.of(
			"matching-service", List.of(KafkaEventRoute.CANDIDATE_WITHDRAW, KafkaEventRoute.CANDIDATE_PROFILE),
			"notification", List.of(KafkaEventRoute.SIGNUP_MAIL, KafkaEventRoute.WITHDRAW_MAIL, KafkaEventRoute.CHAT_NOTIFICATION,
					KafkaEventRoute.ITEMS_GRANTED_MAIL, KafkaEventRoute.ITEMS_GRANTED_PUSH, KafkaEventRoute.REFUND_MAIL),
			"chat-service", List.of(KafkaEventRoute.CHAT_ROOM_CREATE),
			"item-service", List.of(KafkaEventRoute.PRE_SIGNUP_ITEM, KafkaEventRoute.BANK_SCAN));
		expected.forEach((service, routes) -> {
			StandardEnvironment env = load(service, profile, Map.of());
			assertThat(Binder.get(env).bind("comatching.kafka.dlt-routes", Bindable.listOf(KafkaEventRoute.class)).get())
				.containsExactlyElementsOf(routes);
			assertThat(env.getProperty("comatching.kafka.dlt-topics")).isNull();
		});
	}

	static Stream<Arguments> profiles() {
		return SERVICES.stream().flatMap(service -> Stream.of("local", "aws")
			.map(profile -> Arguments.of(service, profile)));
	}

	@ParameterizedTest
	@MethodSource("profiles")
	void environmentVariableSelectsInfrastructure(String service, String profile) {
		StandardEnvironment env = load(service, profile, Map.of());
		boolean local = profile.equals("local");

		assertThat(env.getActiveProfiles()).containsExactly(profile);
		assertThat(env.getProperty("spring.application.name")).isEqualTo(service);
		assertThat(env.getProperty("spring.data.redis.host")).isEqualTo(local ? "localhost" : "redis.example");
		assertThat(env.getProperty("spring.data.redis.port")).isEqualTo(local ? "6380" : "6379");
		assertThat(env.getProperty("spring.data.redis.ssl.enabled")).isEqualTo(local ? "false" : "true");
		assertThat(env.getProperty("management.endpoint.health.show-details")).isEqualTo(local ? "always" : "never");
		assertThat(env.getProperty("management.endpoints.web.exposure.include"))
			.isEqualTo(local ? "health,info,prometheus" : "health,prometheus");
		assertThat(env.getProperty("management.metrics.tags.application")).isEqualTo(service);

		if (!service.equals("gateway-service")) {
			assertThat(env.getProperty("spring.kafka.bootstrap-servers"))
				.isEqualTo(local ? "localhost:9092" : "kafka-1:29092,kafka-2:29092,kafka-3:29092");
			if (!local) {
				assertThat(env.getProperty("spring.kafka.admin.fail-fast")).isEqualTo("true");
			}
		}
		if (List.of("user-service", "matching-service", "item-service").contains(service)) {
			assertThat(env.getProperty("spring.datasource.url"))
				.startsWith(local ? "jdbc:mysql://127.0.0.1:3306/" : "jdbc:mysql://rds.example:3306/");
			assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo(local ? "update" : "validate");
			assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("10");
		}
		if (service.equals("chat-service")) {
			assertThat(env.getProperty("spring.data.mongodb.host")).isEqualTo(local ? "localhost" : "mongodb");
			assertThat(env.getProperty("spring.data.mongodb.auto-index-creation")).isEqualTo("true");
			if (!local) {
				assertThat(env.getProperty("spring.data.mongodb.uri")).isNull();
			}
		}
		if (service.equals("notification")) {
			assertThat(env.getProperty("fcm.required")).isEqualTo(local ? "false" : "true");
			assertThat(env.getProperty("chat-service.url")).isEqualTo(local ? "http://localhost:9003" : "http://chat-service:9003");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"local", "aws"})
	void kakaoCallbackIsIndependentOfFrontendUrl(String profile) {
		StandardEnvironment env = load("user-service", profile, Map.of("CLIENT_URL", "https://frontend.example"));
		assertThat(env.getProperty("client.url")).isEqualTo("https://frontend.example");
		assertThat(env.getProperty(CALLBACK)).isEqualTo(profile.equals("local")
			? "http://localhost:8080/login/oauth2/code/kakao"
			: "https://srv.comatching.site/login/oauth2/code/kakao");
		assertThat(env.getProperty("server.servlet.session.cookie.secure")).isEqualTo(String.valueOf(profile.equals("aws")));
		assertThat(env.getProperty("auth.cookie.secure")).isEqualTo(String.valueOf(profile.equals("aws")));

		StandardEnvironment custom = load("user-service", profile, Map.of(
			"KAKAO_REDIRECT_URI", "https://callback.example/login/oauth2/code/kakao"));
		assertThat(custom.getProperty(CALLBACK)).isEqualTo("https://callback.example/login/oauth2/code/kakao");
	}

	@ParameterizedTest
	@ValueSource(strings = {"local", "aws"})
	void feignImportsAndMatchingPolicySurviveProfileOverrides(String profile) {
		for (String service : List.of("matching-service", "chat-service", "item-service")) {
			StandardEnvironment env = load(service, profile, Map.of());
			assertThat(env.getProperty("spring.cloud.openfeign.client.config.default.connect-timeout")).isEqualTo("1000");
			assertThat(env.getProperty("spring.cloud.openfeign.client.config.default.read-timeout")).isEqualTo("3000");
			if (service.equals("matching-service")) {
				assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
				assertThat(env.getProperty("matching.kafka.candidate-listener-concurrency")).isEqualTo("3");
				KafkaProperties kafka = Binder.get(env).bind("spring.kafka", KafkaProperties.class).get();
				assertThat(kafka.buildConsumerProperties(null))
					.containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "matching-candidate-sync-group")
					.containsEntry(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
						CooperativeStickyAssignor.class.getName());
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"local", "aws"})
	void gatewayUsesSelectedRoutesAndKeepsAwsSwaggerProtection(String profile) {
		StandardEnvironment env = load("gateway-service", profile, Map.of());
		List<Route> routes = Binder.get(env).bind(ROUTES, Bindable.listOf(Route.class))
			.orElseThrow(() -> new IllegalStateException("Gateway routes not bound"));
		Route login = routes.stream().filter(route -> route.getId().equals("user-service-public")).findFirst().orElseThrow();
		assertThat(login.getUri()).isEqualTo(profile.equals("local") ? "http://localhost:9000" : "http://user-service:9000");
		assertThat(login.getPredicates()).anyMatch(predicate -> predicate.contains("/oauth2/**") && predicate.contains("/login/**"));
		if (profile.equals("aws")) {
			assertThat(env.getProperty("management.server.port")).isEqualTo("8081");
			assertThat(routes).filteredOn(route -> route.getId().endsWith("-swagger")).hasSize(3)
				.allSatisfy(route -> {
					assertThat(route.getPredicates()).allMatch(predicate -> predicate.startsWith("Path=/__swagger-off__/"));
					assertThat(route.getFilters()).contains("AuthorizationHeaderFilter");
				});
		}
	}

	@Test
	void missingProfileDoesNotLoadLocalConnections() {
		StandardEnvironment env = load("user-service", null, Map.of());
		assertThat(env.getActiveProfiles()).isEmpty();
		assertThat(env.getProperty("spring.datasource.url")).isNull();
		assertThat(env.getProperty(CALLBACK)).isNull();
		assertThat(env.getProperty("client.url")).isNull();
	}

	private StandardEnvironment load(String service, String profile, Map<String, Object> overrides) {
		Path root = Path.of("").toAbsolutePath();
		while (root != null && !Files.exists(root.resolve("settings.gradle"))) {
			root = root.getParent();
		}
		if (root == null) {
			throw new IllegalStateException("Backend project root not found");
		}
		Map<String, Object> variables = new HashMap<>(Map.of(
			"RDS_ENDPOINT", "rds.example", "REDIS_ENDPOINT", "redis.example"
		));
		if (profile != null) {
			variables.put("SPRING_PROFILES_ACTIVE", profile);
		}
		variables.putAll(overrides);
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
			new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
		env.getPropertySources().addFirst(new MapPropertySource("configLocation", Map.of(
			"spring.config.location", root.resolve(service + "/src/main/resources/").toUri().toString()
		)));
		ConfigDataEnvironmentPostProcessor.applyTo(env);
		return env;
	}

	public static class Route {
		private String id;
		private String uri;
		private List<String> predicates;
		private List<String> filters;

		public String getId() { return id; }
		public void setId(String id) { this.id = id; }
		public String getUri() { return uri; }
		public void setUri(String uri) { this.uri = uri; }
		public List<String> getPredicates() { return predicates; }
		public void setPredicates(List<String> predicates) { this.predicates = predicates; }
		public List<String> getFilters() { return filters; }
		public void setFilters(List<String> filters) { this.filters = filters; }
	}
}
