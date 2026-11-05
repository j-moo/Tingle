package com.e106.tingle.keyring.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

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

import com.e106.tingle.keyring.RegistrationChallenge;

class RegistrationProofVerifierTest {

	private static final String VERSION = KeyringProofTranscript.PROTOCOL_VERSION;
	private static final String NONCE = "A".repeat(43);
	private static final Instant ISSUED_AT = Instant.parse("2030-01-01T00:00:00Z");
	private static final UUID CHALLENGE_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

	private static KeyPair deviceKey;
	private static KeyPair otherDeviceKey;

	private final RegistrationProofVerifier verifier = new RegistrationProofVerifier();

	@BeforeAll
	static void createVirtualDeviceKeys() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
		deviceKey = generator.generateKeyPair();
		otherDeviceKey = generator.generateKeyPair();
	}

	@Test
	void decodesServerSpkiAndHashesTranscriptThenRawSignature() throws Exception {
		RegistrationChallenge challenge = challenge(NONCE, ISSUED_AT);
		KeyringProofTranscript transcript = transcript(challenge);
		byte[] rawSignature = sign(transcript);
		assertThat(deviceKey.getPublic().getEncoded()).hasSize(44);
		assertThat(encode(deviceKey.getPublic().getEncoded())).hasSize(59);
		byte[] expectedEvidence = Arrays.copyOf(transcript.bytes(), transcript.bytes().length + 64);
		System.arraycopy(rawSignature, 0, expectedEvidence, transcript.bytes().length, 64);
		String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expectedEvidence));
		assertThat(verifier.verify(challenge, "serial-1", "device-key", VERSION, encode(rawSignature),
			encode(deviceKey.getPublic().getEncoded()))).contains(expected);
	}

	@Test
	void rejectsNonCanonicalAndInvalidStoredSpkiWithoutThrowing() throws Exception {
		RegistrationChallenge challenge = challenge(NONCE, ISSUED_AT);
		String signature = encode(sign(transcript(challenge)));
		String valid = encode(deviceKey.getPublic().getEncoded());
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
		char nonCanonicalLast = alphabet.charAt(alphabet.indexOf(valid.charAt(58)) | 1);
		for (String key : new String[] {null, "", valid + "=", valid + "\n", valid.substring(1),
			valid.substring(0, 58) + nonCanonicalLast, encode(new byte[44])}) {
			assertThat(verifier.verify(challenge, "serial-1", "device-key", VERSION, signature, key)).isEmpty();
		}
	}

	@Test
	void rejectsAnotherDeviceKeyAndUnsupportedStoredOrRequestProtocol() throws Exception {
		RegistrationChallenge challenge = challenge(NONCE, ISSUED_AT);
		String signature = encode(sign(transcript(challenge)));
		String publicKey = encode(deviceKey.getPublic().getEncoded());
		assertThat(verifier.verify(challenge, "serial-1", "device-key", VERSION, signature,
			encode(otherDeviceKey.getPublic().getEncoded()))).isEmpty();
		assertThat(verifier.verify(challenge, "serial-1", "device-key", "other-version", signature,
			publicKey)).isEmpty();
		RegistrationChallenge unsupported = mock(RegistrationChallenge.class);
		given(unsupported.getExpiresAt()).willReturn(ISSUED_AT.plusSeconds(300));
		given(unsupported.getProtocolVersion()).willReturn("other-version");
		assertThat(verifier.verify(unsupported, "serial-1", "device-key", VERSION, signature,
			publicKey)).isEmpty();
	}

	@Test
	void rebuildsExpectedNonceAndExpiryFromStoredChallenge() throws Exception {
		RegistrationChallenge original = challenge(NONCE, ISSUED_AT);
		String signature = encode(sign(transcript(original)));
		String publicKey = encode(deviceKey.getPublic().getEncoded());
		assertThat(verifier.verify(challenge("B" + NONCE.substring(1), ISSUED_AT), "serial-1", "device-key",
			VERSION, signature, publicKey)).isEmpty();
		assertThat(verifier.verify(challenge(NONCE, ISSUED_AT.plusSeconds(1)), "serial-1", "device-key",
			VERSION, signature, publicKey)).isEmpty();
		assertThat(verifier.verify(original, "other-serial", "device-key", VERSION, signature,
			publicKey)).isEmpty();
	}

	@Test
	void turnsMalformedProofInputsIntoEmptyEvidence() throws Exception {
		RegistrationChallenge challenge = challenge(NONCE, ISSUED_AT);
		String publicKey = encode(deviceKey.getPublic().getEncoded());
		String signature = encode(sign(transcript(challenge)));
		assertThat(verifier.verify(null, "serial-1", "device-key", VERSION, signature, publicKey)).isEmpty();
		assertThat(verifier.verify(challenge, "serial-1", "device-key", null, signature, publicKey)).isEmpty();
		assertThat(verifier.verify(challenge, "serial-1", "device-key", VERSION, "invalid", publicKey)).isEmpty();
		assertThat(verifier.verify(challenge, "serial-1", "bad\nkey", VERSION, signature, publicKey)).isEmpty();
	}

	private static RegistrationChallenge challenge(String nonce, Instant issuedAt) {
		return new RegistrationChallenge(CHALLENGE_ID, 1, UUID.randomUUID(), nonce, issuedAt);
	}

	private static KeyringProofTranscript transcript(RegistrationChallenge challenge) {
		return KeyringProofTranscript.registration("device-key", "serial-1", challenge.getChallengeId(),
			challenge.getNonce(), challenge.getExpiresAt().getEpochSecond());
	}

	private static byte[] sign(KeyringProofTranscript transcript) throws Exception {
		Signature signer = Signature.getInstance("Ed25519");
		signer.initSign(deviceKey.getPrivate());
		signer.update(transcript.bytes());
		return signer.sign();
	}

	private static String encode(byte[] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}
}
