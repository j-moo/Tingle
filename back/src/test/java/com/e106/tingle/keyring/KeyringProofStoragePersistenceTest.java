package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.hibernate.SessionFactory;
import org.hibernate.StaleStateException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.keyring.proof.KeyringProofTranscript;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class KeyringProofStoragePersistenceTest {
	private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
	private static final String NONCE = "A".repeat(43);
	private static final String PROOF = "a".repeat(64);

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Test
	void persistsChallengeOwnershipFiveMinuteExpiryAndOriginalConsumption() {
		RegistrationChallenge challenge = challenge(51, UUID.randomUUID());
		persist(challenge);
		UUID generation = UUID.randomUUID();
		factory().inTransaction(session -> {
			RegistrationChallenge stored = session.find(RegistrationChallenge.class, challenge.getId());
			assertThat(stored.getUserId()).isEqualTo(51);
			assertThat(stored.getExpiresAt()).isEqualTo(NOW.plusSeconds(300));
			assertThat(stored.getProtocolVersion()).isEqualTo(KeyringProofTranscript.PROTOCOL_VERSION);
			assertThat(stored.consume(11, generation, "key-1", PROOF, NOW.plusSeconds(1))).isTrue();
		});
		factory().inTransaction(session -> {
			RegistrationChallenge stored = session.find(RegistrationChallenge.class, challenge.getId());
			assertThat(stored.consume(11, generation, "key-1", PROOF, NOW.plusSeconds(600))).isFalse();
			assertThat(stored.getConsumedAt()).isEqualTo(NOW.plusSeconds(1));
			assertThat(stored.getRegisteredKeyringId()).isEqualTo(11);
			assertThat(stored.getRevision()).isEqualTo(1);
			assertThatThrownBy(() -> stored.consume(12, generation, "key-1", PROOF, NOW.plusSeconds(2)))
				.isInstanceOf(IllegalStateException.class);
		});
	}

	@Test
	void rejectsExpiryAtExactBoundaryAndLaterButAcceptsImmediatelyBefore() {
		for (long offset : List.of(0L, 1L)) {
			RegistrationChallenge challenge = challenge(52, UUID.randomUUID());
			persist(challenge);
			assertThatThrownBy(() -> factory().inTransaction(session -> session.find(
				RegistrationChallenge.class, challenge.getId()).consume(11, UUID.randomUUID(), "key", PROOF,
				NOW.plusSeconds(300).plusNanos(offset)))).isInstanceOf(IllegalStateException.class);
		}
		RegistrationChallenge challenge = challenge(52, UUID.randomUUID());
		persist(challenge);
		factory().inTransaction(session -> assertThat(session.find(RegistrationChallenge.class, challenge.getId())
			.consume(11, UUID.randomUUID(), "key", PROOF, NOW.plusSeconds(300).minusNanos(1000))).isTrue());
	}

	@Test
	void enforcesPublicChallengeAndPerUserRequestUniqueness() {
		UUID request = UUID.randomUUID();
		RegistrationChallenge first = challenge(53, request);
		persist(first);
		assertThatThrownBy(() -> persist(challenge(53, request)))
			.hasStackTraceContaining("uq_registration_challenge_request");
		persist(challenge(54, request));
		assertThatThrownBy(() -> persist(new RegistrationChallenge(first.getChallengeId(), 55,
			UUID.randomUUID(), NONCE, NOW))).hasStackTraceContaining("uq_registration_challenge_public");
	}

	@Test
	void optimisticRevisionAllowsOnlyOneConcurrentConsumption() throws Exception {
		RegistrationChallenge challenge = challenge(56, UUID.randomUUID());
		persist(challenge);
		CountDownLatch loaded = new CountDownLatch(2);
		CountDownLatch proceed = new CountDownLatch(1);
		try (var workers = Executors.newFixedThreadPool(2)) {
			var attempts = List.of(workers.submit(() -> consumeAfterBothLoad(challenge.getId(), 111, loaded, proceed)),
				workers.submit(() -> consumeAfterBothLoad(challenge.getId(), 112, loaded, proceed)));
			try {
				assertThat(loaded.await(15, TimeUnit.SECONDS)).isTrue();
			} finally {
				proceed.countDown();
			}
			assertThat(List.of(attempts.get(0).get(15, TimeUnit.SECONDS), attempts.get(1).get(15, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(true, false);
		}
		factory().inTransaction(session -> assertThat(session.find(RegistrationChallenge.class, challenge.getId())
			.getRevision()).isEqualTo(1));
	}

	@Test
	void freezesOriginalPayloadHashGenerationAndRequest() {
		byte[] original = "hello".getBytes(StandardCharsets.UTF_8);
		KeyringSyncRun run = run(21, UUID.randomUUID(), 1, UUID.randomUUID(), original);
		original[0] = 0;
		persist(run);
		factory().inTransaction(session -> {
			KeyringSyncRun stored = session.find(KeyringSyncRun.class, run.getId());
			assertThat(stored.getPayload()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
			assertThat(stored.getPayloadHash())
				.isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
			assertThat(stored.getOwnerGeneration()).isEqualTo(run.getOwnerGeneration());
			assertThat(stored.getRequestKey()).isEqualTo(run.getRequestKey());
			assertThat(stored.getState()).isEqualTo(KeyringSyncState.PENDING);
			stored.getPayload()[0] = 0;
		});
		factory().inTransaction(session -> assertThat(session.find(KeyringSyncRun.class, run.getId())
			.getPayload()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void preservesMultipleVersionsAndGenerationsButRejectsDuplicateExecutionAndRequest() {
		UUID generation = UUID.randomUUID();
		UUID request = UUID.randomUUID();
		byte[] payload = new byte[] {1, 2, 3};
		persist(run(22, generation, 1, request, payload));
		assertThatThrownBy(() -> persist(run(22, generation, 1, UUID.randomUUID(), payload)))
			.hasStackTraceContaining("uq_keyring_sync_run_version");
		assertThatThrownBy(() -> persist(run(22, generation, 2, request, payload)))
			.hasStackTraceContaining("uq_keyring_sync_run_request");
		persist(run(22, generation, 2, UUID.randomUUID(), payload));
		persist(run(22, UUID.randomUUID(), 1, request, payload));
		persist(run(23, generation, 1, request, payload));
	}

	@Test
	void persistsFirstAckAndDoesNotDowngradeOrRewriteItOnRetry() {
		KeyringSyncRun run = run(24, UUID.randomUUID(), 1, UUID.randomUUID(), new byte[] {1});
		persist(run);
		UUID ackKey = UUID.randomUUID();
		factory().inTransaction(session -> {
			KeyringSyncRun stored = session.find(KeyringSyncRun.class, run.getId());
			assertThat(stored.markFailed()).isTrue();
			assertThat(stored.acknowledge(ackKey, PROOF, NOW.plusSeconds(1))).isTrue();
		});
		factory().inTransaction(session -> {
			KeyringSyncRun stored = session.find(KeyringSyncRun.class, run.getId());
			assertThat(stored.getState()).isEqualTo(KeyringSyncState.ACKED);
			assertThat(stored.acknowledge(UUID.randomUUID(), PROOF, NOW.plusSeconds(10))).isFalse();
			assertThat(stored.markFailed()).isFalse();
			assertThat(stored.getAckedAt()).isEqualTo(NOW.plusSeconds(1));
			assertThat(stored.getAckRequestKey()).isEqualTo(ackKey);
			assertThatThrownBy(() -> stored.acknowledge(ackKey, "b".repeat(64), NOW.plusSeconds(2)))
				.isInstanceOf(IllegalStateException.class);
		});
	}

	@Test
	void leavesUnknownLegacyDeviceCredentialAndOwnerGenerationNull() {
		Keyring keyring = new Keyring("legacy-" + UUID.randomUUID(), "old-firmware");
		persist(keyring);
		factory().inTransaction(session -> {
			Keyring stored = session.find(Keyring.class, keyring.getId());
			assertThat(stored.getCredentialKeyId()).isNull();
			assertThat(stored.getCredentialPublicKey()).isNull();
			assertThat(stored.getCredentialRevokedAt()).isNull();
			assertThat(stored.getOwnerGeneration()).isNull();
			assertThat(stored.getFirmwareVersion()).isEqualTo("old-firmware");
			assertThat(stored.getState()).isEqualTo(KeyringState.UNBOUND);
		});
	}

	private boolean consumeAfterBothLoad(long id, long deviceId, CountDownLatch loaded, CountDownLatch proceed) {
		try {
			factory().inTransaction(session -> {
				RegistrationChallenge challenge = session.find(RegistrationChallenge.class, id);
				loaded.countDown();
				try {
					if (!proceed.await(15, TimeUnit.SECONDS)) {
						throw new IllegalStateException("Concurrent test did not start");
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(exception);
				}
				challenge.consume(deviceId, UUID.randomUUID(), "key", PROOF, NOW.plusSeconds(1));
			});
			return true;
		} catch (RuntimeException exception) {
			for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
				if (cause instanceof OptimisticLockException || cause instanceof StaleStateException) {
					return false;
				}
			}
			throw exception;
		}
	}

	private RegistrationChallenge challenge(long userId, UUID requestKey) {
		return new RegistrationChallenge(UUID.randomUUID(), userId, requestKey, NONCE, NOW);
	}

	private KeyringSyncRun run(long deviceId, UUID generation, long version, UUID requestKey, byte[] payload) {
		return new KeyringSyncRun(UUID.randomUUID(), deviceId, 81, generation, "key", version, payload, NONCE,
			requestKey, NOW);
	}

	private void persist(Object entity) {
		factory().inTransaction(session -> session.persist(entity));
	}

	private SessionFactory factory() {
		return entityManagerFactory.unwrap(SessionFactory.class);
	}
}
