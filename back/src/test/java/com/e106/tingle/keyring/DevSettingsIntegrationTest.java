package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.keyring.proof.KeyringProofTranscript;
import com.e106.tingle.keyring.proof.ServerSettingsSigner;

import tools.jackson.databind.JsonNode;

/** 합성 전용 서버·기기 키와 격리 PostgreSQL로 개발 설정 발급→STORED ACK를 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import({TestcontainersConfiguration.class, DevSettingsIntegrationTest.SigningConfiguration.class})
class DevSettingsIntegrationTest extends IntegrationTestSupport {

	@Autowired
	private KeyPair settingsServerKeys;

	@BeforeEach
	void freezeTimeToWholeSeconds() {
		given(clock.instant()).willReturn(Instant.now().truncatedTo(ChronoUnit.SECONDS));
	}

	@Test
	void issuesOneByteOnPayloadBoundToActualDeviceAndChallenge() throws Exception {
		Fixture fixture = syncPendingUser();
		String challenge = nonce(1);
		JsonNode run = create(fixture, UUID.randomUUID(), challenge, 201);
		assertThat(run.path("payload").asString()).isEqualTo("AQ");
		assertThat(run.path("payloadFormat").asString()).isEqualTo("tingle-dev-settings-v1");
		assertThat(run.path("payloadHash").asString())
			.isEqualTo("4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a");
		assertThat(run.path("deviceNonce").asString()).isEqualTo(challenge);
		assertThat(run.path("serverProofProtocolVersion").asString()).isEqualTo("tingle-dev-settings-proof-v1");
		assertThat(run.path("keyId").asString()).isEqualTo(fixture.keyId());
		assertThat(run.path("serverKeyId").asString()).startsWith("dev-ed25519-");
		assertThat(run.path("serverKeyId").asString()).isNotEqualTo(fixture.keyId());
		assertServerSignature(fixture, run);
		assertThat(ackVersion(fixture)).isNull();
	}

	@Test
	void freezesHttpRetryButRejectsReusingItsKeyForADifferentChallenge() throws Exception {
		Fixture fixture = syncPendingUser();
		UUID key = UUID.randomUUID();
		JsonNode first = create(fixture, key, nonce(1), 201);
		Instant later = clock.instant().plusSeconds(5);
		given(clock.instant()).willReturn(later);
		assertThat(create(fixture, key, nonce(1), 200)).isEqualTo(first);
		createCall(fixture, key, nonce(2)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
		assertThat(runCount(fixture)).isEqualTo(1);
	}

	@Test
	void newDeviceChallengeReusesFrozenRunAndPayloadButGetsNewProof() throws Exception {
		Fixture fixture = syncPendingUser();
		JsonNode first = create(fixture, UUID.randomUUID(), nonce(1), 201);
		Instant later = clock.instant().plusSeconds(5);
		given(clock.instant()).willReturn(later);
		JsonNode next = create(fixture, UUID.randomUUID(), nonce(2), 200);
		for (String field : List.of("syncRunId", "settingsVersion", "payload", "payloadHash", "nonce")) {
			assertThat(next.path(field)).as(field).isEqualTo(first.path(field));
		}
		assertThat(next.path("deviceNonce").asString()).isEqualTo(nonce(2));
		assertThat(next.path("serverSignature")).isNotEqualTo(first.path("serverSignature"));
		assertServerSignature(fixture, next);
		assertThat(runCount(fixture)).isEqualTo(1);
	}

	@Test
	void onToOffAdvancesVersionRejectsOldAckAndAcceptsActualStoredProof() throws Exception {
		Fixture fixture = syncPendingUser();
		JsonNode on = create(fixture, UUID.randomUUID(), nonce(1), 201);
		String settingsPath = me(fixture.tokens(), "/settings");
		String etag = perform(get(settingsPath), fixture.tokens(), null).andExpect(status().isOk())
			.andReturn().getResponse().getHeader("ETag");
		perform(put(settingsPath).header("If-Match", etag), fixture.tokens(),
			toJson(Map.of("matchingEnabled", false, "requirePartnerPhoto", false))).andExpect(status().isOk());
		JsonNode off = create(fixture, UUID.randomUUID(), nonce(2), 201);
		assertThat(off.path("settingsVersion").asLong()).isGreaterThan(on.path("settingsVersion").asLong());
		assertThat(off.path("syncRunId")).isNotEqualTo(on.path("syncRunId"));
		assertThat(off.path("payload").asString()).isEqualTo("AA");
		assertThat(off.path("payloadHash").asString())
			.isEqualTo("6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d");
		assertServerSignature(fixture, off);
		ackCall(fixture, on, UUID.randomUUID(), ackBody(fixture, on)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SYNC_VERSION_STALE"));
		assertThat(ackVersion(fixture)).isNull();
		UUID ackKey = UUID.randomUUID();
		String body = ackBody(fixture, off);
		JsonNode accepted = jsonMapper.readTree(ackCall(fixture, off, ackKey, body).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ACKED")).andReturn().getResponse().getContentAsString());
		// Development discovery settings have not applied the full matching payload or trusted time.
		assertThat(ackVersion(fixture)).isNull();
		perform(get(me(fixture.tokens(), "/onboarding")), fixture.tokens(), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.nextStep").value("SYNC_PENDING"));
		JsonNode replay = jsonMapper.readTree(ackCall(fixture, off, ackKey, body).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		assertThat(replay.path("ackedAt")).isEqualTo(accepted.path("ackedAt"));
	}

	@Test
	void rejectsMissingMalformedAndClientControlledPayloadBeforeCreatingRun() throws Exception {
		Fixture fixture = syncPendingUser();
		for (String body : new String[] {null, "{}", "{\"deviceNonce\":null}", "{\"deviceNonce\":\"bad\"}",
			toJson(Map.of("deviceNonce", nonce(1) + "=")),
			toJson(Map.of("deviceNonce", nonce(1), "payload", "AQ"))}) {
			perform(post(basePath(fixture)).header("Idempotency-Key", UUID.randomUUID()), fixture.tokens(), body)
				.andExpect(status().isBadRequest());
		}
		assertThat(runCount(fixture)).isZero();
	}

	@Test
	void anotherLoggedInOwnerCannotObtainTheDevicesSignedSettings() throws Exception {
		Fixture owner = syncPendingUser();
		Fixture stranger = syncPendingUser();
		perform(post(basePath(owner)).header("Idempotency-Key", UUID.randomUUID()), stranger.tokens(),
			toJson(Map.of("deviceNonce", nonce(1)))).andExpect(status().isNotFound());
		assertThat(runCount(owner)).isZero();
	}

	private void assertServerSignature(Fixture fixture, JsonNode run) throws Exception {
		// Assemble the published IoT transcript independently from the production encoder.
		String text = String.join("\n", "TINGLE-KEYRING-SERVER-SETTINGS", "tingle-dev-settings-proof-v1",
			run.path("serverKeyId").asString(), fixture.serial(), fixture.keyId(), Long.toString(fixture.deviceId()),
			run.path("ownerGeneration").asString(), run.path("syncRunId").asString(),
			Long.toString(run.path("settingsVersion").asLong()), run.path("payloadFormat").asString(),
			run.path("payloadHash").asString(), run.path("nonce").asString(), run.path("deviceNonce").asString(),
			Long.toString(Instant.parse(run.path("serverTime").asString()).getEpochSecond())) + "\n";
		Signature verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(settingsServerKeys.getPublic());
		verifier.update(text.getBytes(StandardCharsets.UTF_8));
		assertThat(verifier.verify(Base64.getUrlDecoder().decode(run.path("serverSignature").asString()))).isTrue();
	}

	private Fixture syncPendingUser() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());
		long userId = userIdOf(tokens);
		String serial = "sync-" + newSocialId();
		String keyId = "key-" + newSocialId();
		KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		long deviceId = jdbc.queryForObject("insert into keyring(serial_number,firmware_version,state,"
			+ "credential_key_id,credential_public_key) values (?,'sync-test','UNBOUND',?,?) returning id",
			Long.class, serial, keyId, encode(keys.getPublic().getEncoded()));
		JsonNode challenge = jsonMapper.readTree(perform(post("/api/keyrings/registration-challenges")
			.header("Idempotency-Key", UUID.randomUUID()), tokens, null).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		byte[] transcript = KeyringProofTranscript.registration(keyId, serial,
			UUID.fromString(challenge.path("challengeId").asString()), challenge.path("nonce").asString(),
			Instant.parse(challenge.path("expiresAt").asString()).getEpochSecond()).bytes();
		String registration = toJson(Map.of("challengeId", challenge.path("challengeId").asString(),
			"deviceSerial", serial, "proof", Map.of("protocolVersion", "tingle-device-proof-v1",
				"keyId", keyId, "signature", sign(keys, transcript))));
		perform(post("/api/keyrings").header("Idempotency-Key", UUID.randomUUID()), tokens, registration)
			.andExpect(status().isCreated());
		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk());
		completeProfile(tokens).andExpect(status().isOk());
		finishSurvey(userId);
		setPreferences(userId);
		UUID generation = jdbc.queryForObject("select owner_generation from keyring where id=?", UUID.class,
			deviceId);
		return new Fixture(tokens, userId, deviceId, serial, keyId, generation, keys);
	}

	private String basePath(Fixture fixture) {
		return "/api/keyrings/" + fixture.deviceId() + "/sync-runs";
	}

	private ResultActions createCall(Fixture fixture, UUID key, String challenge) throws Exception {
		return perform(post(basePath(fixture)).header("Idempotency-Key", key), fixture.tokens(),
			toJson(Map.of("deviceNonce", challenge)));
	}

	private JsonNode create(Fixture fixture, UUID key, String challenge, int code) throws Exception {
		return jsonMapper.readTree(createCall(fixture, key, challenge).andExpect(status().is(code))
			.andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString());
	}

	private String ackBody(Fixture fixture, JsonNode run) throws Exception {
		byte[] transcript = KeyringProofTranscript.stored(fixture.keyId(), fixture.serial(), fixture.deviceId(),
			fixture.generation(), UUID.fromString(run.path("syncRunId").asString()), run.path("nonce").asString(),
			run.path("settingsVersion").asLong(), run.path("payloadHash").asString()).bytes();
		return toJson(Map.of("settingsVersion", run.path("settingsVersion").asLong(),
			"payloadHash", run.path("payloadHash").asString(), "proof",
			Map.of("protocolVersion", "tingle-device-proof-v1", "keyId", fixture.keyId(),
				"signature", sign(fixture.keys(), transcript))));
	}

	private ResultActions ackCall(Fixture fixture, JsonNode run, UUID key, String body) throws Exception {
		return perform(post(basePath(fixture) + "/" + run.path("syncRunId").asString() + "/ack")
			.header("Idempotency-Key", key), fixture.tokens(), body);
	}

	private String sign(KeyPair keys, byte[] transcript) throws Exception {
		Signature signature = Signature.getInstance("Ed25519");
		signature.initSign(keys.getPrivate());
		signature.update(transcript);
		return encode(signature.sign());
	}

	private static String encode(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String nonce(int value) {
		byte[] bytes = new byte[32];
		java.util.Arrays.fill(bytes, (byte)value);
		return encode(bytes);
	}

	private Long ackVersion(Fixture fixture) {
		return jdbc.queryForObject("select synced_settings_version from keyring where id=?", Long.class,
			fixture.deviceId());
	}

	private long runCount(Fixture fixture) {
		return jdbc.queryForObject("select count(*) from keyring_sync_run where owner_user_id=?", Long.class,
			fixture.userId());
	}

	private record Fixture(JsonNode tokens, long userId, long deviceId, String serial, String keyId,
		UUID generation, KeyPair keys) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class SigningConfiguration {
		@Bean
		KeyPair settingsServerKeys() throws Exception {
			return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		}

		@Bean
		DevSettingsIssuer devSettingsIssuer(JdbcTemplate jdbc, KeyPair settingsServerKeys) {
			return new DevSettingsIssuer(jdbc,
				new ServerSettingsSigner(settingsServerKeys.getPrivate(), settingsServerKeys.getPublic()));
		}
	}
}
