package com.comatching.auth.config;

import java.util.List;

import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private static final List<String> AUTH_EXCLUDED_PATHS = List.of(
		"/swagger-ui/**",
		"/v3/api-docs/**",
		"/auth/**"
	);

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
			.csrf(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable);

		http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/favicon.ico", "/error", "/default-ui.css").permitAll()
				.requestMatchers(AUTH_EXCLUDED_PATHS.toArray(String[]::new)).permitAll()
				.requestMatchers("/auth/**").permitAll()
				.anyRequest().authenticated()
			);

		return http.build();
	}
}