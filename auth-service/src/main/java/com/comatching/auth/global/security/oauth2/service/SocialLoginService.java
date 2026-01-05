package com.comatching.auth.global.security.oauth2.service;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import com.comatching.auth.global.security.oauth2.factory.OAuth2UserInfoFactory;
import com.comatching.auth.global.security.oauth2.user.CustomOAuth2User;
import com.comatching.auth.global.security.oauth2.user.OAuth2UserInfo;
import com.comatching.auth.infra.client.MemberServiceClient;
import com.comatching.common.domain.enums.SocialType;
import com.comatching.common.dto.auth.MemberLoginDto;
import com.comatching.common.dto.auth.SocialLoginRequestDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SocialLoginService {

	private final MemberServiceClient memberServiceClient;

	public CustomOAuth2User processUser(ClientRegistration clientRegistration, OAuth2User oAuth2User) {

		String registrationId = clientRegistration.getRegistrationId();

		// Factory를 이용해 유저 정보 표준화
		OAuth2UserInfo userInfo = OAuth2UserInfoFactory.getOAuth2UserInfo(oAuth2User, clientRegistration);

		// Member Service로 보낼 DTO 생성
		SocialLoginRequestDto loginRequest = new SocialLoginRequestDto(
			userInfo.getEmail(),
			userInfo.getNickname(),
			SocialType.from(registrationId),
			userInfo.getProviderId()
		);

		// Member Service 호출
		MemberLoginDto memberDto = memberServiceClient.socialLogin(loginRequest);

		// CustomOAuth2User 반환
		if (oAuth2User instanceof OidcUser oidcUser) {
			return new CustomOAuth2User(
				memberDto,
				oidcUser.getAttributes(),
				oidcUser.getIdToken(),
				oidcUser.getUserInfo()
			);
		}

		return new CustomOAuth2User(memberDto, oAuth2User.getAttributes());
	}
}
