package com.e106.tingle.keyring.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ServerPeerSettingsTranscriptTest {

	private JsonNode fixture;
	private byte[] payload;
	private String[] fields;

	@BeforeEach
	void loadIndependentFirmwareVector() throws Exception {
		try (var input = getClass().getResourceAsStream("/keyring/app-flow-v3-vectors.json")) {
			fixture = JsonMapper.builder().build().readTree(input);
		}
		payload = decode(fixture.get("vectors").get("bundle").get("base64url").asText());
		fields = fixture.get("vectors").get("settingsTranscript").get("utf8").asText().split("\n");
	}

	@Test
	void matchesPublishedOuterTranscriptAndIndependentSignature() throws Exception {
		byte[] transcript = create(fields, payload).bytes();
		assertThat(new String(transcript, StandardCharsets.UTF_8))
			.isEqualTo(fixture.get("vectors").get("settingsTranscript").get("utf8").asText());
		var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
			HexFormat.of().parseHex("302a300506032b6570032100" + fixture.get("serverPublicKeyRawHex").asText())));
		var verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(key);
		verifier.update(transcript);
		assertThat(verifier.verify(decode(fixture.get("vectors").get("settingsSignature").get("base64url").asText())))
			.isTrue();
	}

	@Test
	void rejectsOuterIdentityRunNonceVersionAndEpochThatDoNotMatchGrant() {
		for (int index : new int[] {3, 4, 5, 6, 7, 8, 11, 14}) {
			String[] changed = fields.clone();
			changed[index] = switch (index) {
				case 3 -> "XIAO-0200000000000000";
				case 4 -> "ed25519-0000000000000000";
				case 5 -> "101";
				case 6, 7 -> "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
				case 8 -> "11";
				case 11 -> "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE";
				default -> "2";
			};
			assertThatThrownBy(() -> create(changed, payload)).as("outer field %s", index)
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void rejectsUnboundProfileHashMissingEpochAndUint32Overflow() {
		byte[] altered = payload.clone();
		altered[333] ^= 1;
		assertThatThrownBy(() -> create(fields, altered)).isInstanceOf(IllegalArgumentException.class);
		for (String epoch : new String[] {null, "4294967296"}) {
			String[] changed = fields.clone();
			changed[14] = epoch;
			assertThatThrownBy(() -> create(changed, payload)).isInstanceOf(IllegalArgumentException.class);
		}
		fields[8] = "4294967296";
		assertThatThrownBy(() -> create(fields, payload)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void reconnectKeepsPayloadButUsesNewDeviceNonceWithinGrantValidity() {
		byte[] original = create(fields, payload).bytes();
		fields[12] = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE";
		fields[13] = Long.toString(Long.parseLong(fields[13]) + 1);
		assertThat(create(fields, payload).bytes()).isNotEqualTo(original);
		fields[13] = "1";
		assertThatThrownBy(() -> create(fields, payload)).isInstanceOf(IllegalArgumentException.class);
		fields[13] = Long.toString(Instant.MAX.getEpochSecond());
		assertThatThrownBy(() -> create(fields, payload)).isInstanceOf(IllegalArgumentException.class);
	}

	private static ServerSettingsTranscript create(String[] values, byte[] bundle) {
		return ServerSettingsTranscript.create(values[2], values[3], values[4], Long.parseLong(values[5]),
			UUID.fromString(values[6]), UUID.fromString(values[7]), Long.parseLong(values[8]),
			ServerSettingsTranscript.PEER_PAYLOAD_FORMAT, bundle, values[11], values[12],
			Instant.ofEpochSecond(Long.parseLong(values[13])), values[14] == null ? null : Long.valueOf(values[14]));
	}

	private static byte[] decode(String value) {
		return Base64.getUrlDecoder().decode(value);
	}
}
