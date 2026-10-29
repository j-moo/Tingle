package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KeyringStorageModelTest {
	private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
	private static final String NONCE = "A".repeat(43);
	private static final String PROOF = "a".repeat(64);

	@ParameterizedTest
	@CsvSource({"-1,true", "0,false", "1,false"})
	void enforcesFiveMinuteExpiryBoundary(long offsetSeconds, boolean allowed) {
		RegistrationChallenge challenge = challenge();
		Instant requestAt = NOW.plusSeconds(300 + offsetSeconds);
		if (allowed) {
			assertThat(challenge.consume(5, UUID.randomUUID(), "key", PROOF, requestAt)).isTrue();
		} else {
			assertThatThrownBy(() -> challenge.consume(5, UUID.randomUUID(), "key", PROOF, requestAt))
				.isInstanceOf(IllegalStateException.class);
			assertThat(challenge.getConsumedAt()).isNull();
		}
	}

	@Test
	void retryKeepsFirstResultAndCannotSubstituteDeviceGenerationKeyOrProof() {
		RegistrationChallenge challenge = challenge();
		UUID generation = UUID.randomUUID();
		assertThat(challenge.consume(5, generation, "key", PROOF, NOW.plusSeconds(1))).isTrue();
		assertThat(challenge.consume(5, generation, "key", PROOF, NOW.plusSeconds(600))).isFalse();
		assertThat(challenge.getConsumedAt()).isEqualTo(NOW.plusSeconds(1));
		assertThatThrownBy(() -> challenge.consume(6, generation, "key", PROOF, NOW.plusSeconds(2)))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> challenge.consume(5, UUID.randomUUID(), "key", PROOF, NOW.plusSeconds(2)))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> challenge.consume(5, generation, "other", PROOF, NOW.plusSeconds(2)))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> challenge.consume(5, generation, "key", "b".repeat(64), NOW.plusSeconds(2)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void payloadUsesKnownSha256VectorAndCannotBeMutatedFromEitherArrayReference() {
		byte[] original = "hello".getBytes(StandardCharsets.UTF_8);
		KeyringSyncRun run = run(original);
		original[0] = 0;
		run.getPayload()[0] = 0;
		assertThat(run.getPayload()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
		assertThat(run.getPayloadHash())
			.isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
	}

	@Test
	void verifiedLateAckMayResolveTransferFailureButCanNeverBeDowngradedOrRewritten() {
		KeyringSyncRun run = run(new byte[] {1});
		UUID request = UUID.randomUUID();
		assertThat(run.markFailed()).isTrue();
		assertThat(run.acknowledge(request, PROOF, NOW.plusSeconds(1))).isTrue();
		assertThat(run.acknowledge(UUID.randomUUID(), PROOF, NOW.plusSeconds(2))).isFalse();
		assertThat(run.markFailed()).isFalse();
		assertThat(run.getAckedAt()).isEqualTo(NOW.plusSeconds(1));
		assertThat(run.getAckRequestKey()).isEqualTo(request);
		assertThatThrownBy(() -> run.acknowledge(request, "b".repeat(64), NOW.plusSeconds(2)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void invalidServerInputsCannotCreateConsumptionOrAckEvidence() {
		RegistrationChallenge challenge = challenge();
		assertThatThrownBy(() -> challenge.consume(5, UUID.randomUUID(), "key", PROOF, NOW.minusSeconds(1)))
			.isInstanceOf(IllegalStateException.class);
		assertThat(challenge.getConsumedAt()).isNull();
		KeyringSyncRun run = run(new byte[] {1});
		assertThatThrownBy(() -> run.acknowledge(UUID.randomUUID(), PROOF, NOW.minusSeconds(1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(run.getAckedAt()).isNull();
		assertThat(run.getState()).isEqualTo(KeyringSyncState.PENDING);
	}

	private RegistrationChallenge challenge() {
		return new RegistrationChallenge(UUID.randomUUID(), 1, UUID.randomUUID(), NONCE, NOW);
	}

	private KeyringSyncRun run(byte[] payload) {
		return new KeyringSyncRun(UUID.randomUUID(), 5, 1, UUID.randomUUID(), "key", 1, payload, NONCE,
			UUID.randomUUID(), NOW);
	}
}
