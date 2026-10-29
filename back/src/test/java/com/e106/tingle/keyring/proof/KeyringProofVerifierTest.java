package com.e106.tingle.keyring.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class KeyringProofVerifierTest {

	private static final String VERSION = KeyringProofTranscript.PROTOCOL_VERSION;
	private static final String NONCE = "A".repeat(43);
	private static final String HASH = "a".repeat(64);
	private static final UUID CHALLENGE = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
	private static final UUID OWNER = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
	private static final UUID RUN = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

	private static KeyPair deviceKey;
	private static KeyPair otherDeviceKey;

	private final KeyringProofVerifier verifier = new KeyringProofVerifier();

	@BeforeAll
	static void createVirtualDeviceKeys() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
		deviceKey = generator.generateKeyPair();
		otherDeviceKey = generator.generateKeyPair();
	}

	@Test
	void serializesExactLfTerminatedRegistrationTranscriptAndDefendsItsBytes() {
		KeyringProofTranscript transcript = registration("device-key.1", "serial-1", CHALLENGE, NONCE, 1800000000);
		String expected = "TINGLE-KEYRING-REGISTER\ntingle-device-proof-v1\ndevice-key.1\nserial-1\n"
			+ CHALLENGE + "\n" + NONCE + "\n1800000000\n";
		assertThat(new String(transcript.bytes(), StandardCharsets.UTF_8)).isEqualTo(expected);
		byte[] copy = transcript.bytes();
		copy[0] = 0;
		assertThat(new String(transcript.bytes(), StandardCharsets.UTF_8)).isEqualTo(expected);
	}

	@Test
	void acceptsVirtualDeviceRegistrationAndStoredSignatures() throws Exception {
		KeyringProofTranscript registration = registration("key", "serial", CHALLENGE, NONCE, 1800000000);
		KeyringProofTranscript stored = stored(OWNER, RUN, 1, HASH);
		assertThat(verifier.verify(VERSION, registration, sign(registration), deviceKey.getPublic())).isTrue();
		assertThat(verifier.verify(VERSION, stored, sign(stored), deviceKey.getPublic())).isTrue();
		assertThat(new String(stored.bytes(), StandardCharsets.UTF_8)).isEqualTo(
			"TINGLE-KEYRING-STORED\ntingle-device-proof-v1\nkey\nserial\n17\n" + OWNER + "\n" + RUN
				+ "\n" + NONCE + "\n1\n" + HASH + "\nSTORED\n");
	}

	@Test
	void rejectsAnotherDeviceKeyAndCrossOperationReplay() throws Exception {
		KeyringProofTranscript registration = registration("key", "serial", CHALLENGE, NONCE, 1800000000);
		String signature = sign(registration);
		assertThat(verifier.verify(VERSION, registration, signature, otherDeviceKey.getPublic())).isFalse();
		assertThat(verifier.verify(VERSION, stored(OWNER, RUN, 1, HASH), signature, deviceKey.getPublic())).isFalse();
	}

	@ParameterizedTest
	@MethodSource("changedRegistrationFields")
	void rejectsChangedRegistrationBindings(KeyringProofTranscript changed) throws Exception {
		String signature = sign(registration("key", "serial", CHALLENGE, NONCE, 1800000000));
		assertThat(verifier.verify(VERSION, changed, signature, deviceKey.getPublic())).isFalse();
	}

	static Stream<KeyringProofTranscript> changedRegistrationFields() {
		return Stream.of(registration("other", "serial", CHALLENGE, NONCE, 1800000000),
			registration("key", "other", CHALLENGE, NONCE, 1800000000),
			registration("key", "serial", OWNER, NONCE, 1800000000),
			registration("key", "serial", CHALLENGE, "B" + NONCE.substring(1), 1800000000),
			registration("key", "serial", CHALLENGE, NONCE, 1800000001));
	}

	@ParameterizedTest
	@MethodSource("changedStoredFields")
	void rejectsChangedStoredBindings(KeyringProofTranscript changed) throws Exception {
		assertThat(verifier.verify(VERSION, changed, sign(stored(OWNER, RUN, 1, HASH)),
			deviceKey.getPublic())).isFalse();
	}

	static Stream<KeyringProofTranscript> changedStoredFields() {
		return Stream.of(stored(CHALLENGE, RUN, 1, HASH), stored(OWNER, CHALLENGE, 1, HASH),
			stored(OWNER, RUN, 2, HASH), stored(OWNER, RUN, 1, "b".repeat(64)),
			KeyringProofTranscript.stored("key", "serial", 18, OWNER, RUN, NONCE, 1, HASH),
			KeyringProofTranscript.stored("key", "serial", 17, OWNER, RUN, "B" + NONCE.substring(1), 1, HASH));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"v1", "mock", "tingle-device-proof-v1\n"})
	void rejectsUnsupportedProtocol(String version) throws Exception {
		KeyringProofTranscript transcript = stored(OWNER, RUN, 1, HASH);
		assertThat(verifier.verify(version, transcript, sign(transcript), deviceKey.getPublic())).isFalse();
	}

	@Test
	void rejectsMalformedTamperedAndNonCanonicalSignatureEncoding() throws Exception {
		KeyringProofTranscript transcript = stored(OWNER, RUN, 1, HASH);
		String valid = sign(transcript);
		String changed = (valid.startsWith("A") ? "B" : "A") + valid.substring(1);
		for (String invalid : new String[] {null, "", valid + "=", valid + "\n", valid.substring(1),
			"A".repeat(86), "A".repeat(85) + "B", changed, "!".repeat(86)}) {
			assertThat(verifier.verify(VERSION, transcript, invalid, deviceKey.getPublic())).isFalse();
		}
		assertThat(verifier.verify(VERSION, null, valid, deviceKey.getPublic())).isFalse();
		assertThat(verifier.verify(VERSION, transcript, valid, null)).isFalse();
		assertThat(verifier.verify(VERSION, transcript, valid,
			KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic())).isFalse();
		assertThat(verifier.verify(VERSION, transcript, valid,
			KeyPairGenerator.getInstance("Ed448").generateKeyPair().getPublic())).isFalse();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"key\nextra", "key\r", "공개키", "a b", "a/b", "a+b", "a="})
	void rejectsAmbiguousIdentifiers(String value) {
		assertThatIllegalArgumentException().isThrownBy(() -> registration(value, "serial", CHALLENGE,
			NONCE, 1800000000));
		assertThatIllegalArgumentException().isThrownBy(() -> registration("key", value, CHALLENGE,
			NONCE, 1800000000));
	}

	@Test
	void enforcesIdentifierBoundsAndStrictNonceHashAndNumberEncoding() {
		assertThat(registration("a".repeat(64), "s".repeat(64), CHALLENGE, NONCE, 1)).isNotNull();
		assertThatIllegalArgumentException().isThrownBy(() -> registration("a".repeat(65), "serial",
			CHALLENGE, NONCE, 1));
		assertThatIllegalArgumentException().isThrownBy(() -> registration("key", "s".repeat(65),
			CHALLENGE, NONCE, 1));
		for (String invalid : new String[] {null, "", NONCE + "=", NONCE.substring(1),
			"A".repeat(42) + "B", NONCE + "\n"}) {
			assertThatIllegalArgumentException().isThrownBy(() -> registration("key", "serial", CHALLENGE,
				invalid, 1));
		}
		assertThatIllegalArgumentException().isThrownBy(() -> registration("key", "serial", null, NONCE, 1));
		assertThatIllegalArgumentException().isThrownBy(() -> registration("key", "serial", CHALLENGE, NONCE, 0));
		assertThatIllegalArgumentException().isThrownBy(() -> stored(OWNER, RUN, 0, HASH));
		assertThatIllegalArgumentException().isThrownBy(() -> stored(OWNER, RUN, -1, HASH));
		assertThatIllegalArgumentException().isThrownBy(() -> stored(null, RUN, 1, HASH));
		assertThatIllegalArgumentException().isThrownBy(() -> stored(OWNER, null, 1, HASH));
		assertThatIllegalArgumentException().isThrownBy(() -> stored(OWNER, RUN, 1, "A".repeat(64)));
		assertThatIllegalArgumentException().isThrownBy(() -> stored(OWNER, RUN, 1, "a".repeat(63)));
		assertThatIllegalArgumentException().isThrownBy(() -> KeyringProofTranscript.stored("key", "serial", 0,
			OWNER, RUN, NONCE, 1, HASH));
	}

	private static KeyringProofTranscript registration(String key, String serial, UUID challenge,
		String nonce, long expiresAt) {
		return KeyringProofTranscript.registration(key, serial, challenge, nonce, expiresAt);
	}

	private static KeyringProofTranscript stored(UUID owner, UUID run, long version, String hash) {
		return KeyringProofTranscript.stored("key", "serial", 17, owner, run, NONCE, version, hash);
	}

	private static String sign(KeyringProofTranscript transcript) throws Exception {
		Signature signature = Signature.getInstance("Ed25519");
		signature.initSign(deviceKey.getPrivate());
		signature.update(transcript.bytes());
		return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
	}
}
