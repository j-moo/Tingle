package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.ResultActions;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.peer.PeerSettingsIssuer;
import com.e106.tingle.keyring.proof.KeyringProofTranscript;
import com.e106.tingle.keyring.proof.PeerSettingsCodec;
import com.e106.tingle.settings.snapshot.KeyringCanonicalSnapshotService;

import tools.jackson.databind.JsonNode;

/** Real HTTP/isolated DB with public synthetic keys and a supplied TPS1 snapshot; no external AI/device calls. */
@SpringBootTest(properties = {"app.keyring.peer.lease-seconds=60", "app.keyring.peer.opportunity-seconds=604800",
	"app.keyring.peer.presence-gap-seconds=5"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class PeerSettingsIntegrationTest extends IntegrationTestSupport {

	private static final String PUBLIC = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8";
	@Autowired
	private PeerSettingsIssuer issuer;
	@Autowired
	private KeyringRegistrationRepository devices;
	@MockitoBean
	private KeyringCanonicalSnapshotService snapshots;
	private byte[] profile;

	@BeforeEach
	void supplyPublicSnapshotAndReviewedSchema() throws Exception {
		given(clock.instant()).willReturn(Instant.now().truncatedTo(ChronoUnit.SECONDS));
		if (jdbc.queryForObject("select to_regclass('keyring_peer_owner')::text", String.class) == null) {
			new ResourceDatabasePopulator(new ClassPathResource("database/keyring-peer-ownership-270.sql"))
				.execute(jdbc.getDataSource());
		}
		try (var input = getClass().getResourceAsStream("/keyring/app-flow-v3-vectors.json")) {
			byte[] payload = decode(jsonMapper.readTree(input).path("vectors").path("bundle").path("base64url")
				.asString());
			profile = Arrays.copyOfRange(payload, 4, 200);
		}
		ReflectionTestUtils.setField(issuer, "expectedServerPublicKey", PUBLIC);
	}

	@Test
	void appliedProofCompletesSignupAndCanBeRetried() throws Exception {
		Fixture fixture = fixture();
		JsonNode run = create(fixture, UUID.randomUUID(), nonce(1), 201);
		UUID key = UUID.randomUUID();
		String body = appliedBody(fixture, run, "APPLIED");
		applied(fixture, run, key, body).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SYNC_STORAGE_ACK_REQUIRED"));
		ack(fixture, run).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACKED"));
		perform(get("/api/users/" + fixture.userId() + "/onboarding"), fixture.tokens(), null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.nextStep").value("SYNC_PENDING"));
		applied(fixture, run, key, body).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("select signup_completed_at is not null from users where id=?", Boolean.class,
			fixture.userId())).isTrue();
		perform(get("/api/users/" + fixture.userId() + "/onboarding"), fixture.tokens(), null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.nextStep").value("READY"))
			.andExpect(jsonPath("$.onboardingCompleted").value(true));
		Instant retryTime = clock.instant().plusSeconds(1);
		given(clock.instant()).willReturn(retryTime);
		applied(fixture, run, key, body).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("select synced_settings_version from keyring where id=?", Long.class,
			fixture.deviceId())).isEqualTo(run.path("settingsVersion").asLong());
	}

	@Test
	void rejectsTamperedAndStaleAppliedProofsWithoutUpdatingDevice() throws Exception {
		Fixture fixture = fixture();
		JsonNode run = create(fixture, UUID.randomUUID(), nonce(1), 201);
		ack(fixture, run).andExpect(status().isOk());
		applied(fixture, run, UUID.randomUUID(), appliedBody(fixture, run, "STORED"))
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_DEVICE_PROOF"));
		Instant renewalTime = clock.instant().plusSeconds(61);
		given(clock.instant()).willReturn(renewalTime);
		create(fixture, UUID.randomUUID(), nonce(2), 201);
		applied(fixture, run, UUID.randomUUID(), appliedBody(fixture, run, "APPLIED"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SYNC_VERSION_STALE"));
		assertThat(jdbc.queryForObject("select synced_settings_version from keyring where id=?", Long.class,
			fixture.deviceId())).isNull();
	}

	@Test
	void issuesTsv3AndReusesFrozenGrantWithNewOuterChallenge() throws Exception {
		Fixture fixture = fixture();
		JsonNode first = create(fixture, UUID.randomUUID(), nonce(1), 201);
		byte[] payload = decode(first.path("payload").asString());
		assertThat(payload).hasSize(487);
		assertThat(PeerSettingsCodec.validPayloadFraming(payload)).isTrue();
		assertThat(payload[481]).isEqualTo((byte)1);
		assertThat(payload[482]).isZero();
		assertThat(first.path("serverProofProtocolVersion").asString()).isEqualTo("tingle-dev-settings-proof-v2");
		byte[] grantMessage = new byte[218];
		System.arraycopy("TINGLE-PEER-GRANT-V3\n".getBytes(StandardCharsets.US_ASCII), 0, grantMessage, 0, 21);
		System.arraycopy(payload, 200, grantMessage, 21, 197);
		var verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
			HexFormat.of().parseHex("302a300506032b6570032100" + PUBLIC))));
		verifier.update(grantMessage);
		assertThat(verifier.verify(Arrays.copyOfRange(payload, 397, 461))).isTrue();
		Instant reconnectTime = clock.instant().plusSeconds(1);
		given(clock.instant()).willReturn(reconnectTime);
		JsonNode reconnect = create(fixture, UUID.randomUUID(), nonce(2), 200);
		assertThat(reconnect.path("syncRunId")).isEqualTo(first.path("syncRunId"));
		assertThat(reconnect.path("payload")).isEqualTo(first.path("payload"));
		assertThat(reconnect.path("serverSignature")).isNotEqualTo(first.path("serverSignature"));
	}

	@Test
	void matchingPermissionWaitsForExistingSignupAndRequiredAgreements() throws Exception {
		Fixture fixture = fixture();
		JsonNode pending = create(fixture, UUID.randomUUID(), nonce(1), 201);
		assertThat(decode(pending.path("payload").asString())[482]).isZero();
		jdbc.update("update users set signup_completed_at=? where id=?", java.sql.Timestamp.from(clock.instant()),
			fixture.userId());
		JsonNode ready = create(fixture, UUID.randomUUID(), nonce(2), 201);
		assertThat(decode(ready.path("payload").asString())[482]).isEqualTo((byte)1);
		assertThat(ready.path("settingsVersion").asLong()).isGreaterThan(pending.path("settingsVersion").asLong());
	}

	@Test
	void freezesHttpRetryAndRejectsSameKeyWithDifferentNonceOrFormat() throws Exception {
		Fixture fixture = fixture();
		UUID key = UUID.randomUUID();
		JsonNode first = create(fixture, key, nonce(1), 201);
		assertThat(create(fixture, key, nonce(1), 200)).isEqualTo(first);
		createCall(fixture, key, nonce(2)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
		perform(post(path(fixture)).header("Idempotency-Key", key), fixture.tokens(),
			toJson(Map.of("deviceNonce", nonce(1)))).andExpect(status().isConflict());
	}

	@Test
	void renewsExpiredGrantWithNewVersionAndRejectsOldAck() throws Exception {
		Fixture fixture = fixture();
		JsonNode first = create(fixture, UUID.randomUUID(), nonce(1), 201);
		Instant later = clock.instant().plusSeconds(61);
		given(clock.instant()).willReturn(later);
		JsonNode renewed = create(fixture, UUID.randomUUID(), nonce(2), 201);
		assertThat(renewed.path("settingsVersion").asLong()).isGreaterThan(first.path("settingsVersion").asLong());
		assertThat(renewed.path("payloadHash")).isNotEqualTo(first.path("payloadHash"));
		ack(fixture, first).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SYNC_VERSION_STALE"));
		ack(fixture, renewed).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACKED"));
		assertThat(jdbc.queryForObject("select synced_settings_version from keyring where id=?", Long.class,
			fixture.deviceId())).isNull();
	}

	@Test
	void changedSourceInvalidatesPastAckAndUnmeasuredSourceNeverGetsFallback() throws Exception {
		Fixture fixture = fixture();
		JsonNode first = create(fixture, UUID.randomUUID(), nonce(1), 201);
		byte[] changed = profile.clone();
		changed[195] ^= 1;
		given(snapshots.generateStoragePayload(fixture.userId())).willReturn(changed);
		ack(fixture, first).andExpect(status().isConflict());
		JsonNode next = create(fixture, UUID.randomUUID(), nonce(2), 201);
		assertThat(next.path("settingsVersion").asLong()).isGreaterThan(first.path("settingsVersion").asLong());
		given(snapshots.generateStoragePayload(fixture.userId()))
			.willThrow(new BusinessException(ErrorCode.POLICY_UNRESOLVED));
		createCall(fixture, UUID.randomUUID(), nonce(3)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("POLICY_UNRESOLVED"));
	}

	@Test
	void religionWithdrawalRemovesSignedTsv3EvidenceAndRejectsItsOldAck() throws Exception {
		Fixture fixture = fixture();
		consentOptional(fixture.tokens(), "PROFILE_RELIGION", true).andExpect(status().isOk());
		jdbc.update("update profile set religion='UNDISCLOSED' where user_id=?", fixture.userId());
		byte[] religious = profile.clone();
		religious[184] = 1;
		given(snapshots.generateStoragePayload(fixture.userId())).willReturn(religious);
		JsonNode first = create(fixture, UUID.randomUUID(), nonce(1), 201);
		assertThat(decode(first.path("payload").asString())[188]).isEqualTo((byte)1);
		// Exercise the actual withdrawal API and durable deletion, with synthetic TPS1 supplied above.
		consentOptional(fixture.tokens(), "PROFILE_RELIGION", false).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("select count(*) from keyring_sync_run where keyring_id=?", Long.class,
			fixture.deviceId())).isZero();
		ack(fixture, first).andExpect(status().isNotFound());
		byte[] redacted = profile.clone();
		redacted[184] = 0;
		given(snapshots.generateStoragePayload(fixture.userId())).willReturn(redacted);
		JsonNode next = create(fixture, UUID.randomUUID(), nonce(2), 201);
		assertThat(next.path("settingsVersion").asLong()).isGreaterThan(first.path("settingsVersion").asLong());
		assertThat(decode(next.path("payload").asString())[188]).isZero();
		consentOptional(fixture.tokens(), "PROFILE_RELIGION", false).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("select count(*) from keyring_sync_run where keyring_id=?", Long.class,
			fixture.deviceId())).isEqualTo(1);
	}

	@Test
	void unknownRestrictionReferenceKeepsCompletenessFalseAndDoesNotHideIt() throws Exception {
		Fixture fixture = fixture();
		JsonNode other = login("kakao", newSocialId());
		long target = userIdOf(other);
		jdbc.update("insert into user_block(id,owner_id,target_id,matching_id,created_at) values (?,?,?,?,?)",
			UUID.randomUUID(), fixture.userId(), target, 1L, java.sql.Timestamp.from(clock.instant()));
		JsonNode result = create(fixture, UUID.randomUUID(), nonce(1), 201);
		assertThat(decode(result.path("payload").asString())[481]).isZero();
	}

	@Test
	void refusesUnknownEpochAndMismatchedFullServerKeyWithoutCreatingRun() throws Exception {
		Fixture fixture = fixture();
		jdbc.update("update keyring set ownership_epoch=null where id=?", fixture.deviceId());
		createCall(fixture, UUID.randomUUID(), nonce(1)).andExpect(status().isUnprocessableContent());
		jdbc.update("update keyring set ownership_epoch=0 where id=?", fixture.deviceId());
		ReflectionTestUtils.setField(issuer, "expectedServerPublicKey", "00".repeat(32));
		createCall(fixture, UUID.randomUUID(), nonce(1)).andExpect(status().isUnprocessableContent());
		assertThat(jdbc.queryForObject("select count(*) from keyring_sync_run where keyring_id=?", Long.class,
			fixture.deviceId())).isZero();
	}

	@Test
	void registeredXiaoNeedsNoSerialListButNonXiaoAndUnusableCredentialsAreRefused() throws Exception {
		Fixture fixture = fixture();
		issuer.requireSupported(devices.findById(fixture.deviceId()).orElseThrow());
		jdbc.update("update keyring set serial_number=? where id=?", "NRF-" + fixture.serial().substring(5),
			fixture.deviceId());
		createCall(fixture, UUID.randomUUID(), nonce(1)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("POLICY_UNRESOLVED"));
		jdbc.update("update keyring set serial_number=? where id=?", fixture.serial(), fixture.deviceId());
		String key = jdbc.queryForObject("select credential_public_key from keyring where id=?", String.class,
			fixture.deviceId());
		for (String broken : new String[] {"credential_revoked_at=now()", "credential_public_key=null",
			"credential_key_id=null"}) {
			jdbc.update("update keyring set " + broken + " where id=?", fixture.deviceId());
			Keyring device = devices.findById(fixture.deviceId()).orElseThrow();
			assertThatThrownBy(() -> issuer.requireSupported(device)).isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.POLICY_UNRESOLVED);
			jdbc.update("update keyring set credential_revoked_at=null,credential_public_key=?,credential_key_id=? "
				+ "where id=?", key, fixture.keyId(), fixture.deviceId());
		}
		assertThat(jdbc.queryForObject("select count(*) from keyring_sync_run where keyring_id=?", Long.class,
			fixture.deviceId())).isZero();
	}

	private Fixture fixture() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());
		long userId = userIdOf(tokens);
		KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		String keyId = "ed25519-" + HexFormat.of().formatHex(keys.getPublic().getEncoded(), 12, 20);
		String serial = "XIAO-%016x".formatted(UUID.randomUUID().getLeastSignificantBits());
		UUID generation = UUID.randomUUID();
		long device = jdbc.queryForObject("insert into keyring(serial_number,firmware_version,state,owner_user_id,"
			+ "owner_generation,ownership_epoch,credential_key_id,credential_public_key) "
			+ "values (?,'0.7.2-owner-flow','REGISTERED',?,?,0,?,?) returning id", Long.class, serial, userId,
			generation, keyId, encode(keys.getPublic().getEncoded()));
		patchProfile(tokens, fullProfile(uniqueNickname())).andExpect(status().isOk());
		completeProfile(tokens).andExpect(status().isOk());
		finishSurvey(userId);
		setPreferences(userId);
		given(snapshots.generateStoragePayload(userId)).willReturn(profile);
		return new Fixture(tokens, userId, device, serial, keyId, generation, keys);
	}

	private String path(Fixture fixture) {
		return "/api/keyrings/" + fixture.deviceId() + "/sync-runs";
	}

	private ResultActions createCall(Fixture fixture, UUID key, String challenge) throws Exception {
		return perform(post(path(fixture)).header("Idempotency-Key", key), fixture.tokens(),
			toJson(Map.of("deviceNonce", challenge, "payloadFormat", PeerSettingsCodec.FORMAT)));
	}

	private JsonNode create(Fixture fixture, UUID key, String challenge, int code) throws Exception {
		return jsonMapper.readTree(createCall(fixture, key, challenge).andExpect(status().is(code))
			.andReturn().getResponse().getContentAsString());
	}

	private ResultActions ack(Fixture fixture, JsonNode run) throws Exception {
		byte[] transcript = KeyringProofTranscript.storedV2(fixture.keyId(), fixture.serial(), fixture.deviceId(),
			fixture.generation(), UUID.fromString(run.path("syncRunId").asString()), run.path("nonce").asString(),
			run.path("settingsVersion").asLong(), run.path("payloadHash").asString(), 0).bytes();
		var signature = Signature.getInstance("Ed25519");
		signature.initSign(fixture.keys().getPrivate());
		signature.update(transcript);
		return perform(post(path(fixture) + "/" + run.path("syncRunId").asString() + "/ack")
			.header("Idempotency-Key", UUID.randomUUID()), fixture.tokens(),
			toJson(Map.of("settingsVersion", run.path("settingsVersion").asLong(), "payloadHash",
				run.path("payloadHash").asString(), "proof", Map.of("protocolVersion", "tingle-device-proof-v2",
					"keyId", fixture.keyId(), "signature", encode(signature.sign())))));
	}

	private ResultActions applied(Fixture fixture, JsonNode run, UUID key, String body) throws Exception {
		return perform(post(path(fixture) + "/" + run.path("syncRunId").asString() + "/applied")
			.header("Idempotency-Key", key), fixture.tokens(), body);
	}

	private String appliedBody(Fixture fixture, JsonNode run, String outcome) throws Exception {
		// Build independently from the published firmware wire format.
		String protocol = "tingle-settings-applied-v1";
		long version = run.path("settingsVersion").asLong();
		String hash = run.path("payloadHash").asString();
		String transcript = String.join("\n", "TINGLE-KEYRING-APPLIED", protocol, fixture.keyId(), fixture.serial(),
			Long.toString(fixture.deviceId()), fixture.generation().toString(), "0", run.path("syncRunId").asString(),
			run.path("nonce").asString(), Long.toString(version), hash, outcome) + "\n";
		var signature = Signature.getInstance("Ed25519");
		signature.initSign(fixture.keys().getPrivate());
		signature.update(transcript.getBytes(StandardCharsets.UTF_8));
		return toJson(Map.of("settingsVersion", version, "payloadHash", hash, "proof",
			Map.of("protocolVersion", protocol, "keyId", fixture.keyId(), "signature", encode(signature.sign()))));
	}

	private static String nonce(int value) {
		byte[] nonce = new byte[32];
		Arrays.fill(nonce, (byte)value);
		return encode(nonce);
	}

	private static String encode(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static byte[] decode(String text) {
		return Base64.getUrlDecoder().decode(text);
	}

	private record Fixture(JsonNode tokens, long userId, long deviceId, String serial, String keyId,
		UUID generation, KeyPair keys) {
	}
}
