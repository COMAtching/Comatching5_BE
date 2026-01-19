package com.comatching.notification.global.config;

import java.io.InputStream;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class FCMConfig {

	@PostConstruct
	public void init() {
		try {

			InputStream serviceAccount = new ClassPathResource("serviceAccountKey.json").getInputStream();
			FirebaseOptions options = FirebaseOptions.builder()
				.setCredentials(GoogleCredentials.fromStream(serviceAccount))
				.build();

			if (FirebaseApp.getApps().isEmpty()) {
				FirebaseApp.initializeApp(options);
				log.info("🔥 FirebaseApp Initialized");
			}

		} catch (Exception e) {
			log.error("❌ FirebaseApp Init Failed", e);
		}
	}
}
