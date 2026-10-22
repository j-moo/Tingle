package com.e106.tingle.push;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.api.core.ApiFutures;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;

class FirebasePushGatewayTest {
	private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");

	@Test
	void sendsThroughTheConfiguredSdkButSuppressesExpiredNotifications() {
		FirebaseMessaging messaging = mock(FirebaseMessaging.class);
		when(messaging.sendAsync(any(Message.class))).thenReturn(ApiFutures.immediateFuture("synthetic-provider-id"));
		PushGateway gateway = new FirebasePushGateway(messaging, Clock.fixed(NOW, ZoneOffset.UTC));
		assertThat(gateway.send("synthetic-token", "notice", Map.of("bindingId", "synthetic"), NOW))
			.isEqualTo(PushGateway.Result.REJECTED);
		verifyNoInteractions(messaging);
		assertThat(gateway.send("synthetic-token", "notice", Map.of("bindingId", "synthetic"), NOW.plusSeconds(3600)))
			.isEqualTo(PushGateway.Result.ACCEPTED);
		verify(messaging).sendAsync(any(Message.class));
	}

	@Test
	void distinguishesInvalidTokenFromTransientFailure() {
		FirebaseMessaging messaging = mock(FirebaseMessaging.class);
		FirebaseMessagingException invalid = mock(FirebaseMessagingException.class);
		when(invalid.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
		when(messaging.sendAsync(any(Message.class))).thenReturn(ApiFutures.immediateFailedFuture(invalid));
		PushGateway gateway = new FirebasePushGateway(messaging, Clock.fixed(NOW, ZoneOffset.UTC));
		assertThat(gateway.send("synthetic-token", "notice", Map.of(), NOW.plusSeconds(3600)))
			.isEqualTo(PushGateway.Result.INVALID_TOKEN);
		when(invalid.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNAVAILABLE);
		assertThat(gateway.send("synthetic-token", "notice", Map.of(), NOW.plusSeconds(3600)))
			.isEqualTo(PushGateway.Result.RETRY);
	}
}
