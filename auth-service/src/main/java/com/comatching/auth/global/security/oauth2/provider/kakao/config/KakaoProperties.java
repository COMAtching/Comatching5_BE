package com.comatching.auth.global.security.oauth2.provider.kakao.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kakao.auth")
public record KakaoProperties(
	String adminKey,
	String unlinkUrl,
	String unlinkContentType,
	String unlinkTargetIdType
) {
}
