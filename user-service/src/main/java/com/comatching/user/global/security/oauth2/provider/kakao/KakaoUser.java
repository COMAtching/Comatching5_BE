package com.comatching.user.global.security.oauth2.provider.kakao;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.comatching.user.global.security.oauth2.user.OAuth2ProviderUserInfo;

public class KakaoUser extends OAuth2ProviderUserInfo {

	public KakaoUser(OAuth2User oAuth2User, ClientRegistration clientRegistration) {
		super(oAuth2User.getAttributes(), clientRegistration);
	}

	@Override
	public String getProviderId() {
		return (String)getAttributes().get("sub");
	}

	@Override
	public String getProvider() {
		return "KAKAO";
	}

	@Override
	public String getEmail() {
		return (String)getAttributes().get("email");
	}

	@Override
	public String getNickname() {
		return (String)getAttributes().get("nickname");
	}
}
