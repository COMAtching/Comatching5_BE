package com.comatching.notification.global.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class FCMConfig {

	private final ResourceLoader resourceLoader;

	// 운영: base64 인코딩된 서비스 계정 JSON 을 env 로 주입받는다.
	// (.env 파일이 멀티라인 값을 지원하지 않아 JSON 원문 대신 base64 한 줄로 넣는다)
	@Value("${fcm.service-account-b64:}")
	private String serviceAccountB64;

	// 로컬 폴백: 기존과 같은 classpath 파일.
	@Value("${fcm.credentials:classpath:serviceAccountKey.json}")
	private String credentialsLocation;

	// 운영(aws 프로파일)에서만 true. 키가 없으면 기동을 깨서 배포 시점에 드러낸다.
	@Value("${fcm.required:false}")
	private boolean required;

	@PostConstruct
	public void init() {
		try (InputStream serviceAccount = openCredentials()) {
			if (serviceAccount == null) {
				if (required) {
					throw new IllegalStateException("FCM 서비스 계정 키가 없다 (env FCM_SERVICE_ACCOUNT_B64 미설정)");
				}
				log.warn("FCM 키 없음 - 푸시 비활성화 상태로 기동");
				return;
			}

			FirebaseOptions options = FirebaseOptions.builder()
					.setCredentials(GoogleCredentials.fromStream(serviceAccount))
					.build();

			if (FirebaseApp.getApps().isEmpty()) {
				FirebaseApp.initializeApp(options);
				log.info("🔥 FirebaseApp Initialized");
			}
		} catch (IOException | IllegalArgumentException e) {
			// 키가 "있는데" 깨진 것(base64 오류·JSON 파싱 실패)은 환경 불문 잘못된 상태다. 삼키지 않는다.
			throw new IllegalStateException("FirebaseApp 초기화 실패", e);
		}
	}

	private InputStream openCredentials() throws IOException {
		if (StringUtils.hasText(serviceAccountB64)) {
			return new ByteArrayInputStream(Base64.getDecoder().decode(serviceAccountB64.trim()));
		}
		Resource resource = resourceLoader.getResource(credentialsLocation);
		return resource.exists() ? resource.getInputStream() : null;
	}
}