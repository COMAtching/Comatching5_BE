package com.comatching.common.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public record LoginSession(long memberId, long authVersion, UUID id) {

	public LoginSession {
		if (memberId <= 0 || authVersion < 0 || id == null) {
			throw new IllegalArgumentException("Invalid login session");
		}
	}

	public static final String EXPECTED_HEADER = "X-Expected-Session";

	/** Stable across access-token refreshes. This comparison marker cannot authenticate a request. */
	public String clientMarker() {
		return UUID.nameUUIDFromBytes(("client-session:" + id).getBytes(StandardCharsets.UTF_8)).toString();
	}

	// A member's keys share a Redis Cluster hash slot for atomic session changes.
	private static String prefix(long memberId) {
		return "auth:v2:{" + memberId + "}:";
	}

	public static String versionKey(long memberId) {
		return prefix(memberId) + "version";
	}

	public static String refreshKey(long memberId) {
		return prefix(memberId) + "refresh";
	}

	public String sessionKey() {
		return prefix(memberId) + "session:" + id;
	}
}
