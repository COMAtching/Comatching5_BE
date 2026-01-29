package com.comatching.user.global.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.comatching.user.global.security.filter.CustomJsonUsernamePasswordAuthenticationFilter;
import com.comatching.user.global.security.filter.GatewayAuthenticationFilter;
import com.comatching.user.global.security.handler.LoginFailureHandler;
import com.comatching.user.global.security.handler.LoginSuccessHandler;
import com.comatching.user.global.security.oauth2.handler.CustomOAuth2FailureHandler;
import com.comatching.user.global.security.oauth2.handler.CustomOAuth2SuccessHandler;
import com.comatching.user.global.security.oauth2.service.CustomOAuth2UserService;
import com.comatching.user.global.security.oauth2.service.CustomOidcUserService;
import com.comatching.user.domain.auth.repository.RefreshTokenRepository;
import com.comatching.common.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private final JwtUtil jwtUtil;
	private final ObjectMapper objectMapper;
	private final UserDetailsService userDetailsService;
	private final AuthenticationConfiguration authenticationConfiguration;
	private final RefreshTokenRepository refreshTokenRepository;
	private final CustomOAuth2UserService customOAuth2UserService;
	private final CustomOidcUserService customOidcUserService;
	private final CustomOAuth2SuccessHandler customOAuth2SuccessHandler;
	private final CustomOAuth2FailureHandler customOAuth2FailureHandler;
	private final GatewayAuthenticationFilter gatewayAuthenticationFilter;

	private static final List<String> AUTH_EXCLUDED_PATHS = List.of(
		"/auth-doc/**",
		"/v3/api-docs/**",
		"/api/auth/**",
		"/api/internal/**"
	);

	@Bean
	public AuthenticationManager authenticationManager() throws Exception {
		return authenticationConfiguration.getAuthenticationManager();
	}

	@Bean
	public CustomJsonUsernamePasswordAuthenticationFilter customJsonUsernamePasswordAuthenticationFilter() throws
		Exception {
		CustomJsonUsernamePasswordAuthenticationFilter filter = new CustomJsonUsernamePasswordAuthenticationFilter(
			objectMapper);

		// 매니저 설정
		filter.setAuthenticationManager(authenticationManager());

		// 핸들러 설정
		filter.setAuthenticationSuccessHandler(new LoginSuccessHandler(jwtUtil, objectMapper, refreshTokenRepository));
		filter.setAuthenticationFailureHandler(new LoginFailureHandler(objectMapper));

		return filter;
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
			.csrf(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable);

		http
			.addFilterAt(customJsonUsernamePasswordAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
			.addFilterBefore(gatewayAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

		http
			.oauth2Login(oauth2 -> oauth2
				.userInfoEndpoint(userInfo -> userInfo
					.userService(customOAuth2UserService)
					.oidcUserService(customOidcUserService))
				.successHandler(customOAuth2SuccessHandler)
				.failureHandler(customOAuth2FailureHandler)
			);

		http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/favicon.ico", "/error", "/default-ui.css").permitAll()
				.requestMatchers(AUTH_EXCLUDED_PATHS.toArray(String[]::new)).permitAll()
				.requestMatchers("/auth/**").permitAll()
				.anyRequest().authenticated()
			);

		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
