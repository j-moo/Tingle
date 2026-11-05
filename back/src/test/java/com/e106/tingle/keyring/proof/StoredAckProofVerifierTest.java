package com.e106.tingle.keyring.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.e106.tingle.keyring.KeyringSyncRun;

class StoredAckProofVerifierTest {

	private static final String VERSION = KeyringProofTranscript.PROTOCOL_VERSION;
	private static final String KEY_ID = "device-key";
	private static final String SERIAL = "serial-1";
	private static final String NONCE = "A".repeat(43);
	private static final Instant CREATED_AT = Instant.parse("2030-01-01T00:00:00Z");
	private static final UUID RUN_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
	private static final UUID GENERATION = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
	private static final byte[] PAYLOAD = "synthetic-payload".getBytes(StandardCharsets.UTF_8);

	private static KeyPair deviceKey;
	private static KeyPair otherDeviceKey;

	private final StoredAckProofVerifier verifier = new StoredAckProofVerifier();

	@BeforeAll
	static void createVirtualDeviceKeys() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
		deviceKey = generator.generateKeyPair();
		otherDeviceKey = generator.generateKeyPair();
	}

	@Test
	void decodesServerSpkiAndHashesStoredTranscriptThenRawSignature() throws Exception {
		KeyringSyncRun run = run(GENERATION, NONCE, 7, PAYLOAD);
		KeyringProofTranscript transcript = transcript(run);
		byte[] rawSignature = sign(transcript);
		byte[] expectedEvidence = Arrays.copyOf(transcript.bytes(), transcript.bytes().length + 64);
		System.arraycopy(rawSignature, 0, expectedEvidence, transcript.bytes().length, 64);
		String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expectedEvidence));
		assertThat(encode(deviceKey.getPublic().getEncoded())).hasSize(59);
		assertThat(verifier.verify(run, SERIAL, KEY_ID, VERSION, encode(rawSignature), publicKey())).contains(expected);
	}

	@Test
	void rejectsMalformedAndNonCanonicalStoredPublicKeys() throws Exception {
		KeyringSyncRun run = run(GENERATION, NONCE, 7, PAYLOAD);
		String signature = encode(sign(transcript(run)));
		String valid = publicKey();
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
		char nonCanonicalLast = alphabet.charAt(alphabet.indexOf(valid.charAt(58)) | 1);
		for (String key : new String[] {null, "", valid + "=", valid + "\n", valid.substring(1),
			valid.substring(0, 58) + nonCanonicalLast, encode(new byte[44])}) {
			assertThat(verifier.verify(run, SERIAL, KEY_ID, VERSION, signature, key)).isEmpty();
		}
	}

	@Test
	void rejectsWrongKeyIdentifierAndRequestOrStoredProtocol() throws Exception {
		KeyringSyncRun run = run(GENERATION, NONCE, 7, PAYLOAD);
		String signature = encode(sign(transcript(run)));
		assertThat(verifier.verify(run, SERIAL, KEY_ID, VERSION, signature,
			encode(otherDeviceKey.getPublic().getEncoded()))).isEmpty();
		assertThat(verifier.verify(run, SERIAL, "other-key", VERSION, signature, publicKey())).isEmpty();
		assertThat(verifier.verify(run, SERIAL, KEY_ID, "other-version", signature, publicKey())).isEmpty();
		KeyringSyncRun unsupported = mock(KeyringSyncRun.class);
		given(unsupported.getKeyringId()).willReturn(17L);
		given(unsupported.getSettingsVersion()).willReturn(7L);
		given(unsupported.getCredentialKeyId()).willReturn(KEY_ID);
		given(unsupported.getProtocolVersion()).willReturn("other-version");
		assertThat(verifier.verify(unsupported, SERIAL, KEY_ID, VERSION, signature, publicKey())).isEmpty();
	}

	@Test
	void rejectsRegistrationDomainProofAndMalformedSignature() throws Exception {
		KeyringSyncRun run = run(GENERATION, NONCE, 7, PAYLOAD);
		KeyringProofTranscript registration = KeyringProofTranscript.registration(KEY_ID, SERIAL, RUN_ID,
			NONCE, CREATED_AT.plusSeconds(300).getEpochSecond());
		assertThat(verifier.verify(run, SERIAL, KEY_ID, VERSION, encode(sign(registration)), publicKey())).isEmpty();
		assertThat(verifier.verify(run, SERIAL, KEY_ID, VERSION, "invalid", publicKey())).isEmpty();
		assertThat(verifier.verify(null, SERIAL, KEY_ID, VERSION, encode(sign(transcript(run))),
			publicKey())).isEmpty();
	}

	@Test
	void rebuildsExpectedFieldsFromImmutableServerRun() throws Exception {
		KeyringSyncRun original = run(GENERATION, NONCE, 7, PAYLOAD);
		String signature = encode(sign(transcript(original)));
		for (KeyringSyncRun changed : new KeyringSyncRun[] {
			run(RUN_ID, NONCE, 7, PAYLOAD), run(GENERATION, "B" + NONCE.substring(1), 7, PAYLOAD),
			run(GENERATION, NONCE, 8, PAYLOAD), run(GENERATION, NONCE, 7, new byte[] {1}),
			new KeyringSyncRun(RUN_ID, 18, 1, GENERATION, KEY_ID, 7, PAYLOAD, NONCE,
				UUID.randomUUID(), CREATED_AT),
			new KeyringSyncRun(GENERATION, 17, 1, GENERATION, KEY_ID, 7, PAYLOAD, NONCE,
				UUID.randomUUID(), CREATED_AT)}) {
			assertThat(verifier.verify(changed, SERIAL, KEY_ID, VERSION, signature, publicKey())).isEmpty();
		}
	}

	private static KeyringSyncRun run(UUID generation, String nonce, long version, byte[] payload) {
		return new KeyringSyncRun(RUN_ID, 17, 1, generation, KEY_ID, version, payload, nonce,
			UUID.randomUUID(), CREATED_AT);
	}

	private static KeyringProofTranscript transcript(KeyringSyncRun run) {
		return KeyringProofTranscript.stored(KEY_ID, SERIAL, run.getKeyringId(), run.getOwnerGeneration(),
			run.getSyncRunId(), run.getNonce(), run.getSettingsVersion(), run.getPayloadHash());
	}

	private static byte[] sign(KeyringProofTranscript transcript) throws Exception {
		Signature signer = Signature.getInstance("Ed25519");
		signer.initSign(deviceKey.getPrivate());
		signer.update(transcript.bytes());
		return signer.sign();
	}

	private static String publicKey() {
		return encode(deviceKey.getPublic().getEncoded());
	}

	private static String encode(byte[] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}
}
