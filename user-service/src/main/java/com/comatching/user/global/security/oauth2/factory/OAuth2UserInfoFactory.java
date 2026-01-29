package com.comatching.user.global.security.oauth2.factory;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.comatching.user.global.security.oauth2.provider.kakao.KakaoUser;
import com.comatching.user.global.security.oauth2.user.OAuth2UserInfo;
import com.comatching.common.domain.enums.SocialType;

public class OAuth2UserInfoFactory {

	public static OAuth2UserInfo getOAuth2UserInfo(OAuth2User oAuth2User, ClientRegistration clientRegistration) {

		String registrationId = clientRegistration.getRegistrationId();
		SocialType socialType = SocialType.from(registrationId);

		return switch (socialType) {
			case KAKAO -> new KakaoUser(oAuth2User, clientRegistration);
			default -> throw new IllegalArgumentException("지원하지 않는 socialType: " + socialType);
		};
	}
}
