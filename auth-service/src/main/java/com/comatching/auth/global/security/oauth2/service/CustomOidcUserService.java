package com.comatching.auth.global.security.oauth2.service;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CustomOidcUserService extends OidcUserService {

	private final SocialLoginService socialLoginService;

	@Override
	public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {

		OAuth2User oidcUser = super.loadUser(userRequest);

		return socialLoginService.processUser(userRequest.getClientRegistration(), oidcUser);
	}
}
