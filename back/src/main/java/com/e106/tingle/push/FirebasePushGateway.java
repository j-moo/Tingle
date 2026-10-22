package com.e106.tingle.push;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.api.core.ApiFuture;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;

public class FirebasePushGateway implements PushGateway {
	private final FirebaseMessaging messaging;
	private final Clock clock;

	public FirebasePushGateway(FirebaseMessaging messaging, Clock clock) {
		this.messaging = messaging;
		this.clock = clock;
	}

	@Override
	public boolean ready() {
		return true;
	}

	@Override
	public Result send(String token, String noticeId, Map<String, String> data, Instant deadline) {
		long ttl = Duration.between(clock.instant(), deadline).toMillis();
		if (ttl <= 0) {
			return Result.REJECTED;
		}
		Message message = Message.builder().setToken(token).putAllData(data)
			.setAndroidConfig(AndroidConfig.builder().setTtl(ttl).setCollapseKey(noticeId).build())
			.setApnsConfig(ApnsConfig.builder().putHeader("apns-expiration", Long.toString(deadline.getEpochSecond()))
				.putHeader("apns-collapse-id", noticeId).putHeader("apns-priority", "5")
				.setAps(Aps.builder().setContentAvailable(true).build()).build()).build();
		ApiFuture<String> future = messaging.sendAsync(message);
		try {
			future.get(10, TimeUnit.SECONDS);
			return Result.ACCEPTED;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			future.cancel(true);
			return Result.RETRY;
		} catch (TimeoutException exception) {
			future.cancel(true);
			return Result.RETRY;
		} catch (ExecutionException exception) {
			if (exception.getCause() instanceof FirebaseMessagingException failure) {
				MessagingErrorCode code = failure.getMessagingErrorCode();
				if (code == MessagingErrorCode.UNREGISTERED) {
					return Result.INVALID_TOKEN;
				}
				if (code != MessagingErrorCode.UNAVAILABLE && code != MessagingErrorCode.INTERNAL
					&& code != MessagingErrorCode.QUOTA_EXCEEDED) {
					return Result.REJECTED;
				}
			}
			return Result.RETRY;
		}
	}
}
