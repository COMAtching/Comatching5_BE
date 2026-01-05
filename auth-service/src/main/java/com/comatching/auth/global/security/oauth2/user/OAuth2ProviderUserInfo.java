package com.comatching.auth.global.security.oauth2.user;

import java.util.Map;

import org.springframework.security.oauth2.client.registration.ClientRegistration;

import lombok.Getter;

@Getter
public abstract class OAuth2ProviderUserInfo implements OAuth2UserInfo {

	private final Map<String, Object> attributes;
	private final ClientRegistration clientRegistration;

	public OAuth2ProviderUserInfo(Map<String, Object> attributes, ClientRegistration clientRegistration) {
		this.attributes = attributes;
		this.clientRegistration = clientRegistration;
	}
}
