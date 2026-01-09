package com.comatching.auth.global.security.oauth2.provider.kakao.unlink;

import java.net.URI;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "kakao-auth-client", url = "https://kapi.kakao.com")
public interface KakaoAuthClient {

	@PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	void unlink(
		URI uri,
		@RequestHeader("Authorization") String adminKey,
		@RequestParam("target_id_type") String targetIdType,
		@RequestParam("target_id") String targetId
	);
}
