package com.comatching.common.util;

import org.springframework.util.StringUtils;

public final class AccessTokenDenylistKeys {

	private static final String PREFIX = "auth:access-denylist:";

	private AccessTokenDenylistKeys() {
	}

	public static String key(String tokenId) {
		if (!StringUtils.hasText(tokenId)) {
			throw new IllegalArgumentException("tokenId must not be blank");
		}
		return PREFIX + tokenId;
	}
}
