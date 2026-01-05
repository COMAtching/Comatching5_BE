package com.comatching.common.domain.enums;

public enum SocialType {

	KAKAO,
	NAVER,
	LOCAL
	;

	public static SocialType from(String registrationId) {
		return SocialType.valueOf(registrationId.toUpperCase());
	}
}
