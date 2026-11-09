package com.e106.tingle.keyring.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PeerCommandTranscriptsTest {

	@Test
	void ownerSessionMatchesExactFirmwareVectorAndIndependentSignature() throws Exception {
		JsonNode fixture = fixture("v3");
		String[] fields = fields(fixture, "ownerTranscript");
		byte[] transcript = PeerCommandTranscripts.owner(binding(fields), fields[8], Integer.parseInt(fields[9]),
			Long.parseLong(fields[10]), Long.parseLong(fields[11]));
		verify(fixture, "owner", transcript);
	}

	@Test
	void durableReceiptAndDepartureReleaseMatchBothPublishedFirmwareVectors() throws Exception {
		for (String version : new String[] {"v3", "owner"}) {
			JsonNode fixture = fixture(version);
			String[] receipt = fields(fixture, "receiptTranscript");
			verify(fixture, "receipt", PeerCommandTranscripts.receipt(binding(receipt), uuid(receipt[8]),
				uuid(receipt[9]), receipt[10], uuid(receipt[11]), Long.parseLong(receipt[12])));
			String[] release = fields(fixture, "releaseTranscript");
			verify(fixture, "release", PeerCommandTranscripts.release(binding(release), uuid(release[8]),
				release[9], uuid(release[10]), Long.parseLong(release[11]), release[12]));
		}
	}

	@Test
	void accountLockV2BindsExactHistoricalCredentialHash() throws Exception {
		JsonNode fixture = fixture("owner");
		String[] fields = fields(fixture, "accountLockTranscript");
		assertThat(fields[13]).isEqualTo(fixture.path("vectors").path("peerCredential").path("sha256").asString());
		verify(fixture, "accountLock", PeerCommandTranscripts.restore(binding(fields), uuid(fields[8]),
			fields[9], fields[10], uuid(fields[11]), Long.parseLong(fields[12]), fields[13]));
	}

	@Test
	void rejectsExcessAuthorityNonCanonicalFieldsAndReleaseForTheWrongFingerprintKind() throws Exception {
		String[] fields = fields(fixture("v3"), "ownerTranscript");
		var binding = binding(fields);
		long issued = Long.parseLong(fields[10]);
		assertThatThrownBy(() -> PeerCommandTranscripts.owner(binding, fields[8], 0, issued, issued + 60))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> PeerCommandTranscripts.owner(binding, fields[8], 16, issued, issued + 60))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> PeerCommandTranscripts.owner(binding, fields[8], 1, issued, issued + 301))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> PeerCommandTranscripts.owner(binding, fields[8] + "=", 1, issued, issued + 60))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> PeerCommandTranscripts.receipt(binding, UUID.randomUUID(), UUID.randomUUID(),
			"ABCDEF".repeat(10) + "ABCD", UUID.randomUUID(), issued)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> PeerCommandTranscripts.release(binding, UUID.randomUUID(), "8000000000000001",
			UUID.randomUUID(), issued, "RELEASE_AFTER_DEPARTURE")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> PeerCommandTranscripts.release(binding, UUID.randomUUID(), "0000000000000001",
			UUID.randomUUID(), issued, "ERROR_REVIEWED")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PeerCommandTranscripts.Binding(binding.serverKeyId() + "\n", binding.serial(),
			binding.deviceKeyId(), binding.deviceId(), binding.ownerGeneration(), 1))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static JsonNode fixture(String version) throws Exception {
		try (var input = PeerCommandTranscriptsTest.class.getResourceAsStream(
			"/keyring/app-flow-" + version + "-vectors.json")) {
			return JsonMapper.builder().build().readTree(input);
		}
	}

	private static String[] fields(JsonNode fixture, String name) {
		return fixture.path("vectors").path(name).path("utf8").asString().split("\n");
	}

	private static PeerCommandTranscripts.Binding binding(String[] fields) {
		return new PeerCommandTranscripts.Binding(fields[2], fields[3], fields[4], Long.parseLong(fields[5]),
			uuid(fields[6]), Long.parseLong(fields[7]));
	}

	private static UUID uuid(String value) {
		return UUID.fromString(value);
	}

	private static void verify(JsonNode fixture, String name, byte[] actual) throws Exception {
		JsonNode vectors = fixture.path("vectors");
		assertThat(new String(actual, StandardCharsets.UTF_8))
			.isEqualTo(vectors.path(name + "Transcript").path("utf8").asString());
		byte[] signature = Base64.getUrlDecoder().decode(vectors.path(name + "Signature").path("base64url").asString());
		var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(HexFormat.of().parseHex(
			"302a300506032b6570032100" + fixture.path("serverPublicKeyRawHex").asString())));
		var verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(key);
		verifier.update(actual);
		assertThat(verifier.verify(signature)).isTrue();
		verifier.initVerify(key);
		verifier.update(Arrays.copyOf(actual, actual.length - 1));
		assertThat(verifier.verify(signature)).as("last LF is part of the signature").isFalse();
	}
}
