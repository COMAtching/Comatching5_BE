package com.comatching.user.global.security.oauth2.provider.kakao.unlink;

import java.net.URI;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class KakaoAuthClient {

	private final RestTemplate restTemplate = new RestTemplate();

	public void unlink(URI uri, String adminKey, String targetIdType, String targetId) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.set("Authorization", adminKey);

		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		body.add("target_id_type", targetIdType);
		body.add("target_id", targetId);

		HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

		restTemplate.postForEntity(uri, request, String.class);
	}
}
