package com.e106.tingle.keyring.reset;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.keyring.proof.KeyringProofTranscript;

import tools.jackson.databind.JsonNode;

/** Real Ed25519, HTTP authorization and disposable PostgreSQL; no actual device or team signing key. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class SignedResetIntegrationTest extends IntegrationTestSupport {
	@Test
	void newChallengeReissuesBeforeExpiryAndLateClearedAckCannotReleaseTheNextOwner() throws Exception {
		Fixture fixture = fixture();
		ResetChallenge challenge = challenge(fixture, 0);
		UUID requestKey = UUID.randomUUID();
		String startBody = toJson(Map.of("discardUnsentConfirmed", false, "deviceChallenge", challenge));
		JsonNode first = response(perform(post(path(fixture)).header("Idempotency-Key", requestKey),
			fixture.tokens(), startBody).andExpect(status().isCreated()));
		assertThat(response(perform(post(path(fixture)).header("Idempotency-Key", requestKey),
			fixture.tokens(), startBody).andExpect(status().isOk()))).isEqualTo(first);
		JsonNode firstCommand = jsonMapper.readTree(first.path("deviceCommand").asString());
		assertThat(firstCommand.path("type").asString()).isEqualTo("RESET_DEVICE");
		assertThat(firstCommand.path("recordPolicy").asString()).isEqualTo("REQUIRE_SYNCED");
		assertThat(firstCommand.path("challengeId").asString()).isEqualTo(challenge.challengeId().toString());
		String runPath = path(fixture) + "/" + first.path("resetRunId").asString();
		ResetChallenge replacement = challenge(fixture, 0);
		JsonNode refreshed = response(perform(post(runPath + "/command"), fixture.tokens(),
			toJson(Map.of("deviceChallenge", replacement))).andExpect(status().isOk()));
		assertThat(refreshed.path("resetRunId")).isEqualTo(first.path("resetRunId"));
		assertThat(refreshed.path("resetNonce")).isEqualTo(first.path("resetNonce"));
		JsonNode command = jsonMapper.readTree(refreshed.path("deviceCommand").asString());
		assertThat(command.path("challengeId").asString()).isEqualTo(replacement.challengeId().toString());
		assertThat(command.path("serverSignature")).isNotEqualTo(firstCommand.path("serverSignature"));
		assertThat(refreshed.path("fromOwnershipEpoch").asLong()).isZero();
		assertThat(refreshed.path("toOwnershipEpoch").asLong()).isEqualTo(1);
		ResetDeviceProtocol.Context context = context(fixture, refreshed, command, false);
		String cleared = sign(fixture.keys(), SignedResetDeviceProtocol.clearedTranscript(context));
		String ack = toJson(Map.of("proof", cleared));
		timeShift.set(Duration.ofSeconds(301));
		perform(post(runPath + "/ack"), fixture.tokens(), ack).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("COMPLETED"));
		assertThat(jdbc.queryForObject("select ownership_epoch from keyring where id=?", Long.class, fixture.id()))
			.isEqualTo(1L);
		assertThat(jdbc.queryForObject("select owner_user_id from keyring where id=?", Long.class, fixture.id()))
			.isNull();
		JsonNode nextOwner = login("kakao", newSocialId());
		agree(nextOwner).andExpect(status().isOk());
		registerV2(fixture, nextOwner, 1);
		long nextUser = userIdOf(nextOwner);
		perform(post(runPath + "/ack"), fixture.tokens(), ack).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("select owner_user_id from keyring where id=?", Long.class, fixture.id()))
			.isEqualTo(nextUser);
		perform(post(runPath + "/ack"), fixture.tokens(), toJson(Map.of("proof", "changed")))
			.andExpect(status().isConflict());
		patchProfile(nextOwner, fullProfile(uniqueNickname())).andExpect(status().isOk());
		completeProfile(nextOwner).andExpect(status().isOk());
		finishSurvey(nextUser);
		setPreferences(nextUser);
		String syncPath = "/api/keyrings/" + fixture.id() + "/sync-runs";
		JsonNode run = response(perform(post(syncPath).header("Idempotency-Key", UUID.randomUUID()), nextOwner,
			toJson(Map.of("deviceNonce", nonce()))).andExpect(status().isCreated()));
		assertThat(run.path("ownershipEpoch").asLong()).isEqualTo(1);
		assertThat(run.path("proofProtocolVersion").asString()).isEqualTo("tingle-device-proof-v2");
		assertThat(run.path("serverProofProtocolVersion").asString()).isEqualTo("tingle-dev-settings-proof-v2");
		byte[] stored = KeyringProofTranscript.storedV2(fixture.keyId(), fixture.serial(), fixture.id(),
			UUID.fromString(run.path("ownerGeneration").asString()),
			UUID.fromString(run.path("syncRunId").asString()), run.path("nonce").asString(),
			run.path("settingsVersion").asLong(), run.path("payloadHash").asString(), 1).bytes();
		String syncAck = toJson(Map.of("settingsVersion", run.path("settingsVersion").asLong(),
			"payloadHash", run.path("payloadHash").asString(), "proof", Map.of("protocolVersion",
				"tingle-device-proof-v2", "keyId", fixture.keyId(), "signature", sign(fixture.keys(), stored))));
		perform(post(syncPath + "/" + run.path("syncRunId").asString() + "/ack")
			.header("Idempotency-Key", UUID.randomUUID()), nextOwner, syncAck).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ACKED"));
	}

	@Test
	void unsignedChallengesWrongEpochAndOppositeConsentCannotReleaseOwnership() throws Exception {
		Fixture fixture = fixture();
		perform(post(path(fixture)).header("Idempotency-Key", UUID.randomUUID()), fixture.tokens(),
			"{\"discardUnsentConfirmed\":false}").andExpect(status().isUnprocessableContent());
		perform(post(path(fixture)).header("Idempotency-Key", UUID.randomUUID()), fixture.tokens(),
			toJson(Map.of("discardUnsentConfirmed", false, "deviceChallenge", challenge(fixture, 1))))
			.andExpect(status().isUnprocessableContent());
		JsonNode run = response(perform(post(path(fixture)).header("Idempotency-Key", UUID.randomUUID()),
			fixture.tokens(), toJson(Map.of("discardUnsentConfirmed", true, "deviceChallenge", challenge(fixture, 0))))
			.andExpect(status().isCreated()));
		JsonNode command = jsonMapper.readTree(run.path("deviceCommand").asString());
		ResetDeviceProtocol.Context wrongConsent = context(fixture, run, command, false);
		String ack = toJson(Map.of("proof", sign(fixture.keys(),
			SignedResetDeviceProtocol.clearedTranscript(wrongConsent))));
		perform(post(path(fixture) + "/" + run.path("resetRunId").asString() + "/ack"), fixture.tokens(), ack)
			.andExpect(status().isUnprocessableContent());
		assertThat(jdbc.queryForObject("select owner_user_id from keyring where id=?", Long.class, fixture.id()))
			.isEqualTo(fixture.user());
		assertThat(jdbc.queryForObject("select count(*) from keyring_reset_run where keyring_id=? and completed_at"
			+ " is not null", Long.class, fixture.id())).isZero();
	}

	@Test
	void expiredChallengeNeedsReplacementAndCannotChangeItsExecutionConsent() throws Exception {
		Fixture fixture = fixture();
		ResetChallenge challenge = challenge(fixture, 0);
		UUID key = UUID.randomUUID();
		JsonNode run = response(perform(post(path(fixture)).header("Idempotency-Key", key), fixture.tokens(),
			toJson(Map.of("discardUnsentConfirmed", false, "deviceChallenge", challenge)))
			.andExpect(status().isCreated()));
		timeShift.set(Duration.ofSeconds(61));
		perform(post(path(fixture) + "/" + run.path("resetRunId").asString() + "/command"), fixture.tokens(),
			toJson(Map.of("deviceChallenge", challenge))).andExpect(status().isUnprocessableContent());
		perform(post(path(fixture)).header("Idempotency-Key", key), fixture.tokens(),
			toJson(Map.of("discardUnsentConfirmed", true, "deviceChallenge", challenge)))
			.andExpect(status().isConflict());
	}

	private void registerV2(Fixture fixture, JsonNode tokens, long epoch) throws Exception {
		JsonNode challenge = response(perform(post("/api/keyrings/registration-challenges")
			.header("Idempotency-Key", UUID.randomUUID()), tokens, null).andExpect(status().isCreated()));
		assertThat(challenge.path("proofProtocolVersion").asString()).isEqualTo("tingle-device-proof-v2");
		byte[] transcript = KeyringProofTranscript.registrationV2(fixture.keyId(), fixture.serial(),
			UUID.fromString(challenge.path("challengeId").asString()), challenge.path("nonce").asString(),
			Instant.parse(challenge.path("expiresAt").asString()).getEpochSecond(), epoch).bytes();
		String request = toJson(Map.of("challengeId", challenge.path("challengeId").asString(),
			"deviceSerial", fixture.serial(), "ownershipEpoch", epoch, "ownerState", "UNOWNED", "proof",
			Map.of("protocolVersion", "tingle-device-proof-v2", "keyId", fixture.keyId(),
				"signature", sign(fixture.keys(), transcript))));
		perform(post("/api/keyrings").header("Idempotency-Key", UUID.randomUUID()), tokens, request)
			.andExpect(status().isCreated());
	}

	private ResetDeviceProtocol.Context context(Fixture fixture, JsonNode run, JsonNode command, boolean discard) {
		return new ResetDeviceProtocol.Context(UUID.fromString(run.path("resetRunId").asString()), fixture.id(),
			fixture.user(), fixture.generation(), fixture.serial(), fixture.keyId(), encode(fixture.keys().getPublic()
				.getEncoded()), run.path("resetNonce").asString(), discard, Instant.now(),
			Instant.now().plusSeconds(60),
			run.path("fromOwnershipEpoch").asLong(), UUID.fromString(command.path("challengeId").asString()),
			command.path("deviceNonce").asString());
	}

	private ResetChallenge challenge(Fixture fixture, long epoch) throws Exception {
		ResetChallenge unsigned = new ResetChallenge(SignedResetDeviceProtocol.VERSION, fixture.keyId(),
			fixture.serial(), epoch, fixture.generation().toString(), UUID.randomUUID(), nonce(), 60, "pending");
		return new ResetChallenge(unsigned.resetProtocolVersion(), unsigned.keyId(), unsigned.deviceSerial(), epoch,
			unsigned.localOwnerGeneration(), unsigned.challengeId(), unsigned.deviceNonce(), 60,
			sign(fixture.keys(), SignedResetDeviceProtocol.challengeTranscript(unsigned)));
	}

	private Fixture fixture() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());
		long user = userIdOf(tokens);
		UUID generation = UUID.randomUUID();
		KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		String serial = "reset-" + UUID.randomUUID();
		String keyId = "key-" + UUID.randomUUID();
		long id = jdbc.queryForObject("insert into keyring(serial_number,firmware_version,state,owner_user_id,"
			+ "owner_generation,credential_key_id,credential_public_key) values (?,'test','REGISTERED',?,?,?,?)"
			+ " returning id", Long.class, serial, user, generation, keyId, encode(keys.getPublic().getEncoded()));
		return new Fixture(tokens, user, id, generation, serial, keyId, keys);
	}

	private String path(Fixture fixture) {
		return "/api/keyrings/" + fixture.id() + "/reset-runs";
	}

	private JsonNode response(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private static String sign(KeyPair keys, byte[] bytes) throws Exception {
		Signature signer = Signature.getInstance("Ed25519");
		signer.initSign(keys.getPrivate());
		signer.update(bytes);
		return encode(signer.sign());
	}

	private static String encode(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String nonce() {
		byte[] bytes = new byte[32];
		new java.security.SecureRandom().nextBytes(bytes);
		return encode(bytes);
	}

	private record Fixture(JsonNode tokens, long user, long id, UUID generation, String serial, String keyId,
		KeyPair keys) {
	}
}
