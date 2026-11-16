package com.e106.tingle.push;

import java.io.IOException;
import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

/**
 * 서버 푸시(FCM). 개발·운영은 늘 켜며 프로젝트 ID 와 서비스 계정 키(GOOGLE_APPLICATION_CREDENTIALS)가 없으면 기동하지 않는다.
 * local·test 는 FCM 키가 없으므로 보내지 않는 게이트웨이를 쓴다(LocalPushConfiguration).
 */
@Configuration
@Profile("!local & !test")
public class PushConfiguration {
	@Bean(destroyMethod = "delete")
	FirebaseApp chatPushApp(@Value("${app.push.project-id:}") String projectId) throws IOException {
		if (projectId.isBlank()) {
			throw new IllegalStateException("FIREBASE_PROJECT_ID 값이 필요합니다");
		}
		GoogleCredentials credentials;
		try {
			credentials = GoogleCredentials.getApplicationDefault();
		} catch (IOException exception) {
			throw new IllegalStateException("FCM 서비스 계정 키를 읽지 못했습니다 (GOOGLE_APPLICATION_CREDENTIALS)");
		}
		return FirebaseApp.initializeApp(FirebaseOptions.builder().setProjectId(projectId)
			.setCredentials(credentials).build(), "tingle-chat-push");
	}

	@Bean
	PushGateway firebasePushGateway(FirebaseApp chatPushApp, Clock clock) {
		return new FirebasePushGateway(FirebaseMessaging.getInstance(chatPushApp), clock);
	}
}
