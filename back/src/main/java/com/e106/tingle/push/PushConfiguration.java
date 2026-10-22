package com.e106.tingle.push;

import java.io.IOException;
import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

@Configuration
public class PushConfiguration {
	@Bean(destroyMethod = "delete")
	@ConditionalOnProperty(name = "app.push.enabled", havingValue = "true")
	FirebaseApp chatPushApp(@Value("${app.push.project-id}") String projectId) throws IOException {
		return FirebaseApp.initializeApp(FirebaseOptions.builder().setProjectId(projectId)
			.setCredentials(GoogleCredentials.getApplicationDefault()).build(), "tingle-chat-push");
	}

	@Bean
	@ConditionalOnProperty(name = "app.push.enabled", havingValue = "true")
	PushGateway firebasePushGateway(FirebaseApp chatPushApp, Clock clock) {
		return new FirebasePushGateway(FirebaseMessaging.getInstance(chatPushApp), clock);
	}

	@Bean
	@ConditionalOnProperty(name = "app.push.enabled", havingValue = "false", matchIfMissing = true)
	PushGateway disabledPushGateway() {
		return new PushGateway() {
			@Override
			public boolean ready() {
				return false;
			}

			@Override
			public Result send(String token, String noticeId, java.util.Map<String, String> data,
				java.time.Instant deadline) {
				return Result.RETRY;
			}
		};
	}
}
