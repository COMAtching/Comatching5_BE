package com.comatching.user.global.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "comatching.profile.default-images")
public record ProfileImageProperties(
	String baseUrl,
	List<String> filenames
) {
}
