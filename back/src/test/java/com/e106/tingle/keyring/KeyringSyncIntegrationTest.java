package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.proof.KeyringProofTranscript;

import tools.jackson.databind.JsonNode;

/** 합성 payload 공급 경계와 PostgreSQL 저장·실제 Ed25519 ACK 연결을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class KeyringSyncIntegrationTest extends IntegrationTestSupport {

	@MockitoBean
	private KeyringPayloadProvider payloadProvider;

	@Autowired
	private PlatformTransactionManager transactions;

	@BeforeEach
	void prepareExplicitSyntheticPayload() {
		given(clock.instant()).willReturn(Instant.now().truncatedTo(ChronoUnit.SECONDS));
		given(payloadProvider.prepare(anyLong(), anyLong(), any(UUID.class))).willAnswer(invocation -> {
			long userId = invocation.getArgument(0);
			long deviceId = invocation.getArgument(1);
			UUID generation = invocation.getArgument(2);
			long version = jdbc.queryForObject("select settings_version from user_setting where user_id=?",
				Long.class, userId);
			byte[] bytes = ("synthetic-test-payload-v" + version).getBytes(StandardCharsets.UTF_8);
			return new KeyringPayload(userId, deviceId, generation, version, "synthetic-test-v1", bytes);
		});
	}

	@Test
	void createsImmutableServerPayloadAndReturnsOwnedRunWithoutAcknowledgingDevice() throws Exception {
		Fixture fixture = syncPendingUser();
		JsonNode run = create(fixture, UUID.randomUUID(), 201);
		assertThat(run.path("syncRunId").asString()).isEqualTo(run.path("settingsSnapshotId").asString());
		assertThat(fixture.initialVersion()).isGreaterThan(1);
		assertThat(jdbc.queryForObject("select count(*) from settings_snapshot where user_id=? "
			+ "and settings_version=?", Long.class, fixture.userId(), fixture.initialVersion())).isEqualTo(1);
		assertThat(run.path("deviceId").asLong()).isEqualTo(fixture.deviceId());
		assertThat(run.path("ownerGeneration").asString()).isEqualTo(fixture.generation().toString());
		assertThat(run.path("settingsVersion").asLong()).isEqualTo(fixture.initialVersion());
		assertThat(run.path("currentSettingsVersion").asLong()).isEqualTo(fixture.initialVersion());
		assertThat(run.path("state").asString()).isEqualTo("PENDING");
		assertThat(run.path("stale").asBoolean()).isFalse();
		assertThat(run.path("payloadEncoding").asString()).isEqualTo("base64url");
		assertThat(run.path("payloadFormat").asString()).isEqualTo("synthetic-test-v1");
		byte[] payload = Base64.getUrlDecoder().decode(run.path("payload").asString());
		assertThat(new String(payload, StandardCharsets.UTF_8))
			.isEqualTo("synthetic-test-payload-v" + fixture.initialVersion());
		assertThat(run.path("payloadHash").asString()).isEqualTo(HexFormat.of()
			.formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));
		assertThat(Base64.getUrlDecoder().decode(run.path("nonce").asString())).hasSize(32);
		assertThat(run.path("keyId").asString()).isEqualTo(fixture.keyId());
		assertThat(run.path("proofProtocolVersion").asString()).isEqualTo("tingle-device-proof-v1");
		assertThat(run.path("ackedAt").isNull()).isTrue();
		assertThat(read(fixture, run)).isEqualTo(run);
		assertThat(ackVersion(fixture)).isNull();
		assertStep(fixture, "SYNC_PENDING");
	}

	@Test
	void aliasesPersistSameVersionAndOldKeysStillResolveOriginalRunAfterVersionAdvances() throws Exception {
		Fixture fixture = syncPendingUser();
		UUID firstKey = UUID.randomUUID();
		UUID aliasKey = UUID.randomUUID();
		JsonNode first = create(fixture, firstKey, 201);
		assertThat(create(fixture, aliasKey, 200)).isEqualTo(first);
		advanceVersion(fixture, fixture.initialVersion() + 1);
		JsonNode second = create(fixture, UUID.randomUUID(), 201);
		assertThat(second.path("syncRunId")).isNotEqualTo(first.path("syncRunId"));
		for (UUID key : List.of(firstKey, aliasKey)) {
			JsonNode replay = create(fixture, key, 200);
			assertThat(replay.path("syncRunId")).isEqualTo(first.path("syncRunId"));
			assertThat(replay.path("payload")).isEqualTo(first.path("payload"));
			assertThat(replay.path("nonce")).isEqualTo(first.path("nonce"));
			assertThat(replay.path("settingsVersion").asLong()).isEqualTo(fixture.initialVersion());
			assertThat(replay.path("currentSettingsVersion").asLong()).isEqualTo(fixture.initialVersion() + 1);
			assertThat(replay.path("stale").asBoolean()).isTrue();
		}
		assertThat(runCount(fixture)).isEqualTo(2);
	}

	@Test
	void concurrentCreationWithSameAndDifferentKeysProducesOneVersionSnapshot() throws Exception {
		Fixture fixture = syncPendingUser();
		UUID key = UUID.randomUUID();
		UUID alias = UUID.randomUUID();
		List<JsonNode> results = runConcurrently(() -> createAcceptingEitherStatus(fixture, key),
			() -> createAcceptingEitherStatus(fixture, key),
			() -> createAcceptingEitherStatus(fixture, alias));
		assertThat(results).allSatisfy(result -> assertThat(result).isEqualTo(results.getFirst()));
		assertThat(runCount(fixture)).isEqualTo(1);
		assertThat(create(fixture, alias, 200)).isEqualTo(results.getFirst());
	}

	@Test
	void validStoredAckAdvancesOnlyTargetDeviceAndMakesOnboardingReady() throws Exception {
		Fixture fixture = syncPendingUser();
		long otherDevice = bindKeyring(fixture.userId());
		JsonNode run = create(fixture, UUID.randomUUID(), 201);
		JsonNode result = ack(fixture, run, UUID.randomUUID(), ackBody(fixture, run));
		assertThat(result.path("state").asString()).isEqualTo("ACKED");
		assertThat(result.path("ackedAt").isNull()).isFalse();
		assertThat(ackVersion(fixture)).isEqualTo(fixture.initialVersion());
		assertThat(jdbc.queryForObject("select synced_settings_version from keyring where id=?",
			Long.class, otherDevice)).isNull();
		assertThat(jdbc.queryForObject("select settings_version from user_setting where user_id=?",
			Long.class, fixture.userId())).isEqualTo(fixture.initialVersion());
		assertStep(fixture, "READY");
	}

	@Test
	void profileSnapshotChangeInvalidatesOldRunAndOnlyNewStoredAckRestoresReadiness() throws Exception {
		Fixture fixture = syncPendingUser();
		JsonNode oldRun = create(fixture, UUID.randomUUID(), 201);
		patchProfile(fixture.tokens(), Map.of("smokingStatus", "SMOKER")).andExpect(status().isOk());
		long currentVersion = jdbc.queryForObject("select settings_version from user_setting where user_id=?",
			Long.class, fixture.userId());
		assertThat(currentVersion).isEqualTo(fixture.initialVersion() + 1);
		assertThat(jdbc.queryForObject("select count(*) from settings_snapshot where user_id=? "
			+ "and settings_version=?", Long.class, fixture.userId(), currentVersion)).isEqualTo(1);
		JsonNode stale = read(fixture, oldRun);
		assertThat(stale.path("stale").asBoolean()).isTrue();
		assertThat(stale.path("payload")).isEqualTo(oldRun.path("payload"));
		assertThat(stale.path("nonce")).isEqualTo(oldRun.path("nonce"));
		ackCall(fixture, oldRun, UUID.randomUUID(), ackBody(fixture, oldRun))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SYNC_VERSION_STALE"));
		assertThat(ackVersion(fixture)).isNull();
		assertStep(fixture, "SYNC_PENDING");
		JsonNode currentRun = create(fixture, UUID.randomUUID(), 201);
		assertThat(currentRun.path("settingsVersion").asLong()).isEqualTo(currentVersion);
		ack(fixture, currentRun, UUID.randomUUID(), ackBody(fixture, currentRun));
		assertThat(ackVersion(fixture)).isEqualTo(currentVersion);
		assertStep(fixture, "READY");
	}

	@Test
	void repeatedAndConcurrentAckPreservesFirstStoredTime() throws Exception {
		Fixture fixture = syncPendingUser();
		JsonNode run = create(fixture, UUID.randomUUID(), 201);
		UUID key = UUID.randomUUID();
		String body = ackBody(fixture, run);
		Instant firstTime = clock.instant().plusSeconds(10);
		given(clock.instant()).willReturn(firstTime);
		List<JsonNode> results = runConcurrently(() -> ack(fixture, run, key, body),
			() -> ack(fixture, run, key, body));
		assertThat(results.getFirst().path("ackedAt")).isEqualTo(results.getLast().path("ackedAt"));
		given(clock.instant()).willReturn(firstTime.plusSeconds(60));
		JsonNode replay = ack(fixture, run, key, body);
		assertThat(replay.path("ackedAt").asString()).isEqualTo(firstTime.toString());
		assertThat(jdbc.queryForObject("select synced_at from keyring where id=?", Timestamp.class,
			fixture.deviceId()).toInstant()).isEqualTo(firstTime);
	}

	@Test
	void pendingOldVersionAckFailsWithoutChangingRunOrDevice() throws Exception {
		Fixture fixture = syncPendingUser();
		JsonNode run = create(fixture, UUID.randomUUID(), 201);
		advanceVersion(fixture, fixture.initialVersion() + 1);
		ackCall(fixture, run, UUID.randomUUID(), ackBody(fixture, run))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SYNC_VERSION_STALE"));
		JsonNode current = read(fixture, run);
		assertThat(current.path("state").asString()).isEqualTo("PENDING");
		assertThat(current.path("stale").asBoolean()).isTrue();
		assertThat(current.path("ackedAt").isNull()).isTrue();
		assertThat(ackVersion(fixture)).isNull();
		assertStep(fixture, "SYNC_PENDING");
	}

	@Test
	void oldAcknowledgedReceiptCannotDowngradeNewerDeviceAcknowledgement() throws Exception {
		Fixture fixture = syncPendingUser();
		JsonNode oldRun = create(fixture, UUID.randomUUID(), 201);
		UUID oldAckKey = UUID.randomUUID();
		String oldBody = ackBody(fixture, oldRun);
		JsonNode firstAck = ack(fixture, oldRun, oldAckKey, oldBody);
		advanceVersion(fixture, fixture.initialVersion() + 1);
		JsonNode newRun = create(fixture, UUID.randomUUID(), 201);
		Instant nextTime = clock.instant().plusSeconds(20);
		given(clock.instant()).willReturn(nextTime);
		ack(fixture, newRun, UUID.randomUUID(), ackBody(fixture, newRun));
		Instant newerTime = jdbc.queryForObject("select synced_at from keyring where id=?", Timestamp.class,
			fixture.deviceId()).toInstant();
		JsonNode replay = ack(fixture, oldRun, oldAckKey, oldBody);
		assertThat(replay.path("ackedAt")).isEqualTo(firstAck.path("ackedAt"));
		assertThat(replay.path("stale").asBoolean()).isTrue();
		assertThat(ackVersion(fixture)).isEqualTo(fixture.initialVersion() + 1);
		assertThat(jdbc.queryForObject("select synced_at from keyring where id=?", Timestamp.class,
			fixture.deviceId()).toInstant()).isEqualTo(newerTime);
	}

	@Test
	void foreignMemberCannotReadOrAcknowledgeAnotherOwnersRun() throws Exception {
		Fixture owner = syncPendingUser();
		Fixture other = syncPendingUser();
		JsonNode run = create(owner, UUID.randomUUID(), 201);
		perform(get(runPath(owner, run)), other.tokens(), null).andExpect(status().isNotFound());
		perform(post(runPath(owner, run) + "/ack").header("Idempotency-Key", UUID.randomUUID()),
			other.tokens(), ackBody(owner, run)).andExpect(status().isNotFound());
		assertThat(ackVersion(owner)).isNull();
		assertThat(read(owner, run).path("state").asString()).isEqualTo("PENDING");
	}

	@Test
	void invalidProofWrongVersionAndRevokedCredentialsPreservePendingRun() throws Exception {
		Fixture fixture = syncPendingUser();
		JsonNode run = create(fixture, UUID.randomUUID(), 201);
		KeyPair wrongKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		Fixture wrongSigner = new Fixture(fixture.tokens(), fixture.userId(), fixture.deviceId(),
			fixture.serial(), fixture.keyId(), fixture.generation(), wrongKeys, fixture.initialVersion());
		ackCall(fixture, run, UUID.randomUUID(), ackBody(wrongSigner, run))
			.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_DEVICE_PROOF"));
		long version = run.path("settingsVersion").asLong();
		String wrongVersion = ackBody(fixture, run).replace("\"settingsVersion\":" + version,
			"\"settingsVersion\":" + (version + 1));
		ackCall(fixture, run, UUID.randomUUID(), wrongVersion).andExpect(status().is4xxClientError());
		jdbc.update("update keyring set credential_revoked_at=now() where id=?", fixture.deviceId());
		ackCall(fixture, run, UUID.randomUUID(), ackBody(fixture, run))
			.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_DEVICE_PROOF"));
		assertThat(ackVersion(fixture)).isNull();
		assertThat(jdbc.queryForObject("select state from keyring_sync_run where sync_run_id=?", String.class,
			runId(run))).isEqualTo("PENDING");
	}

	@Test
	void changedOwnershipGenerationPreventsReadingOrAcknowledgingOldRun() throws Exception {
		Fixture fixture = syncPendingUser();
		JsonNode run = create(fixture, UUID.randomUUID(), 201);
		jdbc.update("update keyring set owner_generation=? where id=?", UUID.randomUUID(), fixture.deviceId());
		perform(get(runPath(fixture, run)), fixture.tokens(), null).andExpect(status().is4xxClientError());
		ackCall(fixture, run, UUID.randomUUID(), ackBody(fixture, run)).andExpect(status().is4xxClientError());
		assertThat(ackVersion(fixture)).isNull();
	}

	@Test
	void enforcesAuthenticationOnboardingGateAndRevokedSession() throws Exception {
		Fixture fixture = syncPendingUser();
		JsonNode run = create(fixture, UUID.randomUUID(), 201);
		mockMvc.perform(post(basePath(fixture))).andExpect(status().isUnauthorized());
		mockMvc.perform(get(runPath(fixture, run))).andExpect(status().isUnauthorized());
		JsonNode incomplete = login("kakao", newSocialId());
		agree(incomplete).andExpect(status().isOk());
		perform(post(basePath(fixture)).header("Idempotency-Key", UUID.randomUUID()), incomplete, null)
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
		logout(fixture.tokens().path("refreshToken").asString()).andExpect(status().isNoContent());
		ackCall(fixture, run, UUID.randomUUID(), ackBody(fixture, run)).andExpect(status().isUnauthorized());
		assertThat(ackVersion(fixture)).isNull();
	}

	@Test
	void invalidHeadersAndClientPayloadCannotCreateOrAcknowledgeRuns() throws Exception {
		Fixture fixture = syncPendingUser();
		perform(post(basePath(fixture)), fixture.tokens(), null).andExpect(status().isBadRequest());
		perform(post(basePath(fixture)).header("Idempotency-Key", "invalid"), fixture.tokens(), null)
			.andExpect(status().isBadRequest());
		perform(post(basePath(fixture)).header("Idempotency-Key", UUID.randomUUID()), fixture.tokens(),
			"{\"payload\":\"client-supplied\"}").andExpect(status().isBadRequest());
		assertThat(runCount(fixture)).isZero();
		JsonNode run = create(fixture, UUID.randomUUID(), 201);
		perform(post(runPath(fixture, run) + "/ack"), fixture.tokens(), ackBody(fixture, run))
			.andExpect(status().isBadRequest());
		String valid = ackBody(fixture, run);
		String unknown = valid.substring(0, valid.length() - 1) + ",\"publicKey\":\"untrusted\"}";
		ackCall(fixture, run, UUID.randomUUID(), unknown).andExpect(status().isBadRequest());
		assertThat(ackVersion(fixture)).isNull();
	}

	@Test
	void unresolvedPayloadBoundaryCreatesNoPartialRunOrAcknowledgement() throws Exception {
		Fixture fixture = syncPendingUser();
		given(payloadProvider.prepare(anyLong(), anyLong(), any(UUID.class)))
			.willThrow(new BusinessException(ErrorCode.POLICY_UNRESOLVED));
		perform(post(basePath(fixture)).header("Idempotency-Key", UUID.randomUUID()), fixture.tokens(), null)
			.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("POLICY_UNRESOLVED"));
		assertThat(runCount(fixture)).isZero();
		assertThat(ackVersion(fixture)).isNull();
		assertStep(fixture, "SYNC_PENDING");
	}

	@Test
	void transactionRollbackRestoresCreationAndAcknowledgementTogetherWithDevicePointer() throws Exception {
		Fixture fixture = syncPendingUser();
		UUID createKey = UUID.randomUUID();
		TransactionTemplate transaction = new TransactionTemplate(transactions);
		assertThatThrownBy(() -> transaction.executeWithoutResult(state -> {
			try {
				JsonNode run = create(fixture, createKey, 201);
				ack(fixture, run, UUID.randomUUID(), ackBody(fixture, run));
			} catch (Exception exception) {
				throw new IllegalStateException("unexpected API failure", exception);
			}
			throw new IllegalStateException("synthetic rollback");
		})).isInstanceOf(IllegalStateException.class).hasMessage("synthetic rollback");
		assertThat(runCount(fixture)).isZero();
		assertThat(ackVersion(fixture)).isNull();
		assertStep(fixture, "SYNC_PENDING");
		create(fixture, createKey, 201);
	}

	private Fixture syncPendingUser() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());
		long userId = userIdOf(tokens);
		String serial = "sync-" + newSocialId();
		String keyId = "key-" + newSocialId();
		KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		String publicKey = encode(keys.getPublic().getEncoded());
		long deviceId = jdbc.queryForObject("insert into keyring(serial_number,firmware_version,state,"
			+ "credential_key_id,credential_public_key) values (?,'sync-test','UNBOUND',?,?) returning id",
			Long.class, serial, keyId, publicKey);
		String challengeBody = perform(post("/api/keyrings/registration-challenges")
			.header("Idempotency-Key", UUID.randomUUID()), tokens, null).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		JsonNode challenge = jsonMapper.readTree(challengeBody);
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
		long initialVersion = jdbc.queryForObject("select settings_version from user_setting where user_id=?",
			Long.class, userId);
		return new Fixture(tokens, userId, deviceId, serial, keyId, generation, keys, initialVersion);
	}

	private String basePath(Fixture fixture) {
		return "/api/keyrings/" + fixture.deviceId() + "/sync-runs";
	}

	private String runPath(Fixture fixture, JsonNode run) {
		return basePath(fixture) + "/" + run.path("syncRunId").asString();
	}

	private JsonNode create(Fixture fixture, UUID key, int expectedStatus) throws Exception {
		return jsonMapper.readTree(perform(post(basePath(fixture)).header("Idempotency-Key", key),
			fixture.tokens(), null).andExpect(status().is(expectedStatus))
			.andExpect(header().string("Cache-Control", "no-store")).andExpect(header().exists("X-Request-Id"))
			.andReturn().getResponse().getContentAsString());
	}

	private JsonNode createAcceptingEitherStatus(Fixture fixture, UUID key) throws Exception {
		ResultActions result = perform(post(basePath(fixture)).header("Idempotency-Key", key), fixture.tokens(), null);
		assertThat(result.andReturn().getResponse().getStatus()).isIn(200, 201);
		return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private JsonNode read(Fixture fixture, JsonNode run) throws Exception {
		return jsonMapper.readTree(perform(get(runPath(fixture, run)), fixture.tokens(), null)
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(header().exists("X-Request-Id")).andReturn().getResponse().getContentAsString());
	}

	private String ackBody(Fixture fixture, JsonNode run) throws Exception {
		byte[] transcript = KeyringProofTranscript.stored(fixture.keyId(), fixture.serial(), fixture.deviceId(),
			fixture.generation(), runId(run), run.path("nonce").asString(), run.path("settingsVersion").asLong(),
			run.path("payloadHash").asString()).bytes();
		return toJson(Map.of("settingsVersion", run.path("settingsVersion").asLong(),
			"payloadHash", run.path("payloadHash").asString(), "proof",
			Map.of("protocolVersion", "tingle-device-proof-v1", "keyId", fixture.keyId(),
				"signature", sign(fixture.keys(), transcript))));
	}

	private ResultActions ackCall(Fixture fixture, JsonNode run, UUID key, String body) throws Exception {
		return perform(post(runPath(fixture, run) + "/ack").header("Idempotency-Key", key), fixture.tokens(), body);
	}

	private JsonNode ack(Fixture fixture, JsonNode run, UUID key, String body) throws Exception {
		return jsonMapper.readTree(ackCall(fixture, run, key, body).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store")).andExpect(header().exists("X-Request-Id"))
			.andReturn().getResponse().getContentAsString());
	}

	private String sign(KeyPair keys, byte[] transcript) throws Exception {
		Signature signature = Signature.getInstance("Ed25519");
		signature.initSign(keys.getPrivate());
		signature.update(transcript);
		return encode(signature.sign());
	}

	private String encode(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private UUID runId(JsonNode run) {
		return UUID.fromString(run.path("syncRunId").asString());
	}

	private Long ackVersion(Fixture fixture) {
		return jdbc.queryForObject("select synced_settings_version from keyring where id=?", Long.class,
			fixture.deviceId());
	}

	private long runCount(Fixture fixture) {
		return jdbc.queryForObject("select count(*) from keyring_sync_run where owner_user_id=?", Long.class,
			fixture.userId());
	}

	private void advanceVersion(Fixture fixture, long version) {
		jdbc.update("update user_setting set settings_version=? where user_id=?", version, fixture.userId());
	}

	private void assertStep(Fixture fixture, String step) throws Exception {
		perform(get(me(fixture.tokens(), "/onboarding")), fixture.tokens(), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.nextStep").value(step));
	}

	private record Fixture(JsonNode tokens, long userId, long deviceId, String serial, String keyId,
		UUID generation, KeyPair keys, long initialVersion) {
	}
}
