package com.comatching.user.domain.member.service;

import java.nio.charset.StandardCharsets;

import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

import com.comatching.user.global.config.ProfileImageProperties;

final class DefaultProfileImageUrlBuilder {

	private DefaultProfileImageUrlBuilder() {
	}

	static String build(ProfileImageProperties profileImageProperties, String filename) {
		if (!StringUtils.hasText(profileImageProperties.baseUrl())) {
			return null;
		}

		String baseUrl = profileImageProperties.baseUrl().trim();
		if (!baseUrl.endsWith("/")) {
			baseUrl += "/";
		}

		String encodedFilename = UriUtils.encodePathSegment(filename, StandardCharsets.UTF_8);
		return baseUrl + encodedFilename;
	}
}
