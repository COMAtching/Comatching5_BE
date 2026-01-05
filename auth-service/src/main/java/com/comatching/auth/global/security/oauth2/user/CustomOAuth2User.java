package com.comatching.auth.global.security.oauth2.user;

import java.util.Map;

import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.comatching.auth.global.security.UserPrincipal;
import com.comatching.common.domain.enums.SocialType;
import com.comatching.common.dto.auth.MemberLoginDto;

import lombok.Getter;

@Getter
public class CustomOAuth2User extends UserPrincipal implements OidcUser, OAuth2User {

	private final Map<String, Object> attributes;
	private final OidcIdToken idToken;
	private final OidcUserInfo userInfo;

	public CustomOAuth2User(MemberLoginDto memberDto, Map<String, Object> attributes, OidcIdToken idToken, OidcUserInfo userInfo) {
		super(memberDto);
		this.attributes = attributes;
		this.idToken = idToken;
		this.userInfo = userInfo;
	}

	public CustomOAuth2User(MemberLoginDto memberDto, Map<String, Object> attributes) {
		super(memberDto);
		this.attributes = attributes;
		this.idToken = null;
		this.userInfo = null;
	}

	@Override
	public Map<String, Object> getAttributes() {
		return attributes;
	}

	@Override
	public Map<String, Object> getClaims() {
		return this.idToken != null ? this.idToken.getClaims() : null;
	}

	@Override
	public OidcIdToken getIdToken() {
		return this.idToken;
	}

	@Override
	public String getName() {
		return String.valueOf(super.getId());
	}

	@Override
	public OidcUserInfo getUserInfo() {
		return this.userInfo;
	}
}
