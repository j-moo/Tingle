package com.e106.tingle.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class PendingEmailsTest {

	private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-05T00:00:00Z"));
	private final PendingEmails emails = new PendingEmails(new Clock() {
		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now.get();
		}
	});

	private static final UUID SESSION = UUID.randomUUID();

	@Test
	void takeOnceWithinTtlThenGone() {
		emails.hold(1L, "a@example.com", SESSION);
		assertThat(emails.take(1L, SESSION::equals)).isEqualTo("a@example.com");
		assertThat(emails.take(1L, SESSION::equals)).isNull();
	}

	@Test
	void emailFromOlderLoginSessionIsDropped() {
		emails.hold(1L, "old@example.com", SESSION);
		assertThat(emails.take(1L, session -> false)).isNull();
		assertThat(emails.size()).isZero();
	}

	@Test
	void expiredOrClearedEmailIsNotReturned() {
		emails.hold(1L, "a@example.com", SESSION);
		now.set(now.get().plus(PendingEmails.TTL).plus(Duration.ofSeconds(1)));
		assertThat(emails.take(1L, SESSION::equals)).isNull();
		emails.purgeExpired();
		assertThat(emails.size()).isZero();

		emails.hold(2L, "b@example.com", SESSION);
		emails.hold(2L, null, SESSION);
		assertThat(emails.take(2L, SESSION::equals)).isNull();
	}
}
