package com.e106.tingle.keyring.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ServerSettingsSignerTest {
	private static final UUID OWNER = UUID.fromString("33333333-3333-4333-8333-333333333333");
	private static final UUID RUN = UUID.fromString("22222222-2222-4222-8222-222222222222");
	private static final String NONCE = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
	private static final String DEVICE_NONCE = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE";
	private static final Instant TIME = Instant.parse("2026-11-04T00:00:00Z");

	@Test
	void independentIotAndSyntheticVectorsMatchExactContractAndVerify() throws Exception {
		try (var input = getClass().getResourceAsStream("/keyring/server-settings-v1-vectors.json")) {
			var vectors = JsonMapper.builder().build().readTree(input);
			assertThat(vectors.get("fixtures").size()).isEqualTo(2);
			for (var fixture : vectors.get("fixtures")) {
				var request = fixture.get("request");
				var transcript = from(request);
				assertThat(new String(transcript.bytes(), StandardCharsets.UTF_8))
					.isEqualTo(fixture.get("transcriptUtf8").asText());
				assertThat(encode(transcript.bytes())).isEqualTo(fixture.get("transcriptBase64url").asText());
				assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(transcript.bytes())))
					.isEqualTo(fixture.get("transcriptSha256").asText());
				var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
					decode(fixture.get("publicKeySpkiBase64url").asText())));
				assertThat(HexFormat.of().formatHex(key.getEncoded(), 12, 44))
					.isEqualTo(fixture.get("publicKeyRawHex").asText());
				byte[] signature = decode(request.get("serverSignature").asText());
				assertThat(verifies(key, transcript.bytes(), signature)).isTrue();
				String[] lines = fixture.get("transcriptUtf8").asText().split("\n");
				assertThat(lines).hasSize(14);
				for (int index = 0; index < lines.length; index++) {
					String[] changed = lines.clone();
					changed[index] += "0";
					byte[] altered = (String.join("\n", changed) + "\n").getBytes(StandardCharsets.UTF_8);
					assertThat(verifies(key, altered, signature)).as("signed line %s", index).isFalse();
				}
			}
		}
	}

	@Test
	void dedicatedSignerDerivesKeyIdAndRejectsOtherKeyPairsAndValidFieldChanges() throws Exception {
		var generator = KeyPairGenerator.getInstance("Ed25519");
		var keys = generator.generateKeyPair();
		var other = generator.generateKeyPair();
		var signer = new ServerSettingsSigner(keys.getPrivate(), keys.getPublic());
		assertThat(signer.publicKeyRawHex()).hasSize(64);
		assertThat(signer.keyId()).isEqualTo("dev-ed25519-" + signer.publicKeyRawHex().substring(0, 16));
		assertThat(signer.publicKeySpkiBase64url()).isEqualTo(encode(keys.getPublic().getEncoded()));
		var original = transcript(signer.keyId(), 42, OWNER, new byte[] {1}, DEVICE_NONCE, TIME);
		String signature = signer.sign(original);
		assertThat(signature).hasSize(86).doesNotContain("=");
		assertThat(signer.sign(original)).isEqualTo(signature);
		assertThat(verifies(keys.getPublic(), original.bytes(), decode(signature))).isTrue();
		for (var candidate : new ServerSettingsTranscript[] {
			transcript(signer.keyId(), 43, OWNER, new byte[] {1}, DEVICE_NONCE, TIME),
			transcript(signer.keyId(), 42, RUN, new byte[] {1}, DEVICE_NONCE, TIME),
			transcript(signer.keyId(), 42, OWNER, new byte[] {0}, DEVICE_NONCE, TIME),
			transcript(signer.keyId(), 42, OWNER, new byte[] {1}, NONCE, TIME),
			transcript(signer.keyId(), 42, OWNER, new byte[] {1}, DEVICE_NONCE, TIME.plusSeconds(1))}) {
			assertThat(verifies(keys.getPublic(), candidate.bytes(), decode(signature))).isFalse();
		}
		assertThatThrownBy(() -> new ServerSettingsSigner(keys.getPrivate(), other.getPublic()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> signer.sign(transcript("other-key", 42, OWNER, new byte[] {1}, DEVICE_NONCE, TIME)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ServerSettingsSigner(null, keys.getPublic()))
			.isInstanceOf(IllegalArgumentException.class);
		var ed448 = KeyPairGenerator.getInstance("Ed448").generateKeyPair();
		assertThatThrownBy(() -> new ServerSettingsSigner(ed448.getPrivate(), ed448.getPublic()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNonCanonicalInputsAndUnsupportedDevicePayload() {
		assertThatThrownBy(() -> transcript("bad\nkey", 42, OWNER, new byte[] {1}, DEVICE_NONCE, TIME))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> transcript("key", 0, OWNER, new byte[] {1}, DEVICE_NONCE, TIME))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> transcript("key", 42, OWNER, new byte[] {2}, DEVICE_NONCE, TIME))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> transcript("key", 42, OWNER, new byte[] {1, 0}, DEVICE_NONCE, TIME))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> transcript("key", 42, OWNER, new byte[] {1}, DEVICE_NONCE + "=", TIME))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> transcript("key", 42, OWNER, new byte[] {1}, DEVICE_NONCE, TIME.plusNanos(1)))
			.isInstanceOf(IllegalArgumentException.class);
		var transcript = transcript("key", 42, OWNER, new byte[] {1}, DEVICE_NONCE, TIME);
		byte[] copy = transcript.bytes();
		copy[0] = 0;
		assertThat(transcript.bytes()[0]).isEqualTo((byte)'T');
		assertThat(transcript.toString()).doesNotContain("XIAO");
	}

	private ServerSettingsTranscript from(JsonNode request) {
		return ServerSettingsTranscript.create(request.get("serverKeyId").asText(), "XIAO-0000000000000001",
			request.get("keyId").asText(), request.get("deviceId").asLong(),
			UUID.fromString(request.get("ownerGeneration").asText()),
			UUID.fromString(request.get("syncRunId").asText()),
			request.get("settingsVersion").asLong(), request.get("payloadFormat").asText(),
			decode(request.get("payload").asText()), request.get("nonce").asText(),
			request.get("deviceNonce").asText(), Instant.parse(request.get("serverTime").asText()));
	}

	private ServerSettingsTranscript transcript(String keyId, long device, UUID owner, byte[] payload,
		String deviceNonce, Instant time) {
		return ServerSettingsTranscript.create(keyId, "XIAO-0000000000000001", "ed25519-0000000000000001",
			device, owner, RUN, 7, ServerSettingsTranscript.PAYLOAD_FORMAT, payload, NONCE, deviceNonce, time);
	}

	private boolean verifies(PublicKey key, byte[] transcript, byte[] signature) throws Exception {
		var verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(key);
		verifier.update(transcript);
		return verifier.verify(signature);
	}

	private static byte[] decode(String text) {
		return Base64.getUrlDecoder().decode(text);
	}

	private static String encode(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
