package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.keyring.dto.RegisterKeyringRequest;
import com.e106.tingle.keyring.dto.RegistrationDeviceProof;
import com.e106.tingle.keyring.proof.KeyringProofTranscript;

import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class KeyringRegistrationIntegrationTest extends IntegrationTestSupport {

	private static final String PATH = "/api/keyrings";

	@Autowired
	private KeyringRegistrationService service;

	@Autowired
	private PlatformTransactionManager transactions;

	@Test
	void blockedDeviceCannotRegisterAfterCredentialReinstatementOrManualOwnershipRestoration() throws Exception {
		JsonNode user = eligibleUser();
		Device device = trustedDevice();
		JsonNode challenge = issue(user);
		String body = request(challenge, device);
		jdbc.update("update keyring set permanently_blocked_at=now(),credential_revoked_at=null where id=?",
			device.id());
		expectError(user, UUID.randomUUID(), body, 409, "KEYRING_PERMANENTLY_BLOCKED");
		assertThat(consumed(challenge)).isFalse();
		jdbc.update("update keyring set state='REGISTERED',owner_user_id=?,owner_generation=? where id=?",
			userIdOf(user), UUID.randomUUID(), device.id());
		expectError(user, UUID.randomUUID(), body, 409, "KEYRING_PERMANENTLY_BLOCKED");
		assertThat(consumed(challenge)).isFalse();
	}

	@Test
	void permanentBlockAlsoRejectsThePreviouslySuccessfulRegistrationReceipt() throws Exception {
		JsonNode user = eligibleUser();
		Device device = trustedDevice();
		String body = request(issue(user), device);
		UUID key = UUID.randomUUID();
		register(user, key, body);
		jdbc.update("update keyring set permanently_blocked_at=now(),credential_revoked_at=null where id=?",
			device.id());
		expectError(user, key, body, 409, "KEYRING_PERMANENTLY_BLOCKED");
		assertThat(jdbc.queryForObject("select permanently_blocked_at is not null from keyring where id=?",
			Boolean.class, device.id())).isTrue();
	}

	@Test
	void registersNumericDeviceAndReceiptWithoutAcknowledgingSettingsAndAdvancesOnboarding() throws Exception {
		JsonNode user = eligibleUser();
		Device device = trustedDevice();
		JsonNode challenge = issue(user);
		UUID requestKey = UUID.randomUUID();
		JsonNode response = register(user, requestKey, request(challenge, device));
		assertThat(response.size()).isEqualTo(3);
		assertThat(response.path("deviceId").asLong()).isEqualTo(device.id());
		assertThat(response.path("status").asString()).isEqualTo("SYNC_PENDING");
		assertThat(response.path("firmwareVersion").asString()).isEqualTo("test-fw");
		assertThat(owner(device)).isEqualTo(userIdOf(user));
		assertThat(generation(device)).isNotNull();
		assertThat(consumed(challenge)).isTrue();
		assertThat(jdbc.queryForMap("select synced_settings_version,synced_at from keyring where id=?",
			device.id()).values()).containsOnlyNulls();
		assertThat(jdbc.queryForObject("select settings_version from user_setting where user_id=?",
			Long.class, userIdOf(user))).isZero();
		assertThat(jdbc.queryForObject("select registration_request_key from registration_challenge "
			+ "where challenge_id=?", UUID.class, challengeId(challenge))).isEqualTo(requestKey);
		perform(get(me(user, "/onboarding")), user, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.nextStep").value("PROFILE_REQUIRED"));
	}

	@Test
	void concurrentSameKeyReturnsOneOriginalReceiptAndOwnershipGeneration() throws Exception {
		JsonNode user = eligibleUser();
		Device device = trustedDevice();
		JsonNode challenge = issue(user);
		String body = request(challenge, device);
		UUID key = UUID.randomUUID();
		List<JsonNode> results = runConcurrently(() -> register(user, key, body),
			() -> register(user, key, body), () -> register(user, key, body));
		assertThat(results).allSatisfy(result -> assertThat(result).isEqualTo(results.getFirst()));
		assertThat(jdbc.queryForObject("select registered_owner_generation from registration_challenge "
			+ "where challenge_id=?", UUID.class, challengeId(challenge))).isEqualTo(generation(device));
		assertThat(jdbc.queryForObject("select count(*) from registration_challenge where "
			+ "user_id=? and registration_request_key=?", Long.class, userIdOf(user), key)).isEqualTo(1);
	}

	@Test
	void replayKeepsOriginalFirmwareAndStatusAfterExpiryAndSettingChanges() throws Exception {
		JsonNode user = eligibleUser();
		Instant now = fixedNow();
		Device device = trustedDevice();
		JsonNode challenge = issue(user);
		String body = request(challenge, device);
		UUID key = UUID.randomUUID();
		JsonNode original = register(user, key, body);
		jdbc.update("update keyring set firmware_version='changed',synced_settings_version=4,synced_at=now() "
			+ "where id=?", device.id());
		jdbc.update("update user_setting set settings_version=4 where user_id=?", userIdOf(user));
		given(clock.instant()).willReturn(now.plusSeconds(301));
		assertThat(register(user, key, body)).isEqualTo(original);
	}

	@Test
	void absentSettingsReturnsRegisteredAndReplaysThatFirstStatus() throws Exception {
		JsonNode user = eligibleUser();
		jdbc.update("delete from user_setting where user_id=?", userIdOf(user));
		Device device = trustedDevice();
		String body = request(issue(user), device);
		UUID key = UUID.randomUUID();
		JsonNode original = register(user, key, body);
		assertThat(original.path("status").asString()).isEqualTo("REGISTERED");
		jdbc.update("insert into user_setting values (?,true,true,1)", userIdOf(user));
		assertThat(register(user, key, body)).isEqualTo(original);
	}

	@Test
	void sameKeyAcrossDifferentChallengeOrBodyConflictsAndConsumedChallengeRejectsNewKey() throws Exception {
		JsonNode user = eligibleUser();
		Device first = trustedDevice();
		JsonNode challenge = issue(user);
		UUID key = UUID.randomUUID();
		String original = request(challenge, first);
		register(user, key, original);
		Device other = trustedDevice();
		expectError(user, key, request(challenge, other), 409, "IDEMPOTENCY_CONFLICT");
		JsonNode next = issue(user);
		expectError(user, key, request(next, other), 409, "IDEMPOTENCY_CONFLICT");
		expectError(user, UUID.randomUUID(), original, 409, null);
		assertThat(consumed(next)).isFalse();
		assertThat(owner(other)).isNull();
	}

	@Test
	void simultaneousOwnersProduceOneBindingAndLeaveLosersChallengeUnconsumed() throws Exception {
		JsonNode first = eligibleUser();
		JsonNode second = eligibleUser();
		Device device = trustedDevice();
		JsonNode firstChallenge = issue(first);
		JsonNode secondChallenge = issue(second);
		String firstBody = request(firstChallenge, device);
		String secondBody = request(secondChallenge, device);
		List<Integer> results = runConcurrently(
			() -> call(first, UUID.randomUUID(), firstBody).andReturn().getResponse().getStatus(),
			() -> call(second, UUID.randomUUID(), secondBody).andReturn().getResponse().getStatus());
		assertThat(results).containsExactlyInAnyOrder(201, 409);
		assertThat(consumed(firstChallenge)).isEqualTo(results.getFirst() == 201);
		assertThat(consumed(secondChallenge)).isEqualTo(results.getLast() == 201);
		assertThat(owner(device)).isEqualTo(results.getFirst() == 201 ? userIdOf(first) : userIdOf(second));
	}

	@Test
	void rejectsWrongSignatureUnknownSerialUnknownKeyAndRevokedCredentialWithoutConsumption() throws Exception {
		JsonNode user = eligibleUser();
		Device device = trustedDevice();
		JsonNode challenge = issue(user);
		Device wrongSigner = new Device(device.id(), device.serial(), device.keyId(),
			KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
		expectError(user, UUID.randomUUID(), request(challenge, wrongSigner), 422, "INVALID_DEVICE_PROOF");
		Device unknownKey = new Device(device.id(), device.serial(), "unknown", device.keys());
		expectError(user, UUID.randomUUID(), request(challenge, unknownKey), 422, "INVALID_DEVICE_PROOF");
		Device unknownSerial = new Device(device.id(), "unknown-" + newSocialId(), device.keyId(), device.keys());
		expectError(user, UUID.randomUUID(), request(challenge, unknownSerial), 422, "INVALID_DEVICE_PROOF");
		jdbc.update("update keyring set credential_revoked_at=now() where id=?", device.id());
		expectError(user, UUID.randomUUID(), request(challenge, device), 422, "INVALID_DEVICE_PROOF");
		assertThat(consumed(challenge)).isFalse();
		assertThat(owner(device)).isNull();
	}

	@Test
	void hidesForeignAndMissingChallengesWithoutChangingOwner() throws Exception {
		JsonNode user = eligibleUser();
		JsonNode foreign = eligibleUser();
		Device device = trustedDevice();
		JsonNode challenge = issue(foreign);
		expectError(user, UUID.randomUUID(), request(challenge, device), 404, null);
		RegistrationDeviceProof proof = signedProof(challenge, device);
		String missing = toJson(new RegisterKeyringRequest(UUID.randomUUID().toString(), device.serial(), proof));
		expectError(user, UUID.randomUUID(), missing, 404, null);
		assertThat(consumed(challenge)).isFalse();
		assertThat(owner(device)).isNull();
	}

	@Test
	void acceptsAt299SecondsAndRejectsAtExactly300Seconds() throws Exception {
		JsonNode user = eligibleUser();
		Instant now = fixedNow();
		Device first = trustedDevice();
		Device second = trustedDevice();
		JsonNode firstChallenge = issue(user);
		JsonNode secondChallenge = issue(user);
		given(clock.instant()).willReturn(now.plusSeconds(299));
		register(user, UUID.randomUUID(), request(firstChallenge, first));
		given(clock.instant()).willReturn(now.plusSeconds(300));
		expectError(user, UUID.randomUUID(), request(secondChallenge, second), 422, "INVALID_DEVICE_PROOF");
		assertThat(consumed(secondChallenge)).isFalse();
		assertThat(owner(second)).isNull();
	}

	@Test
	void requiresAuthenticationAgreementAndActiveSession() throws Exception {
		mockMvc.perform(post(PATH)).andExpect(status().isUnauthorized());
		JsonNode user = eligibleUser();
		String body = request(issue(user), trustedDevice());
		JsonNode noAgreement = login("kakao", newSocialId());
		expectError(noAgreement, UUID.randomUUID(), body, 403, "ONBOARDING_REQUIRED");
		logout(user.path("refreshToken").asString()).andExpect(status().isNoContent());
		expectError(user, UUID.randomUUID(), body, 401, null);
	}

	@Test
	void rejectsMissingNonCanonicalHeaderUnknownPublicKeyAndLegacyPayload() throws Exception {
		JsonNode user = eligibleUser();
		Device device = trustedDevice();
		JsonNode challenge = issue(user);
		String body = request(challenge, device);
		perform(post(PATH), user, body).andExpect(status().isBadRequest());
		for (String key : List.of("invalid", "1-1-1-1-1", UUID.randomUUID().toString().replace("-", ""))) {
			perform(post(PATH).header("Idempotency-Key", key), user, body)
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
		String extra = body.substring(0, body.length() - 1) + ",\"publicKey\":\"untrusted\"}";
		expectError(user, UUID.randomUUID(), extra, 400, "INVALID_REQUEST");
		expectError(user, UUID.randomUUID(), toJson(Map.of("deviceId", device.id(), "nonce", "old")),
			400, "INVALID_REQUEST");
		assertThat(consumed(challenge)).isFalse();
		assertThat(owner(device)).isNull();
		perform(post(PATH).header("Idempotency-Key", UUID.randomUUID().toString().toUpperCase()), user, body)
			.andExpect(status().isCreated()).andExpect(jsonPath("$.deviceId").value(device.id()));
	}

	@Test
	void rollbackRestoresBothChallengeAndDeviceAndAllowsRetry() throws Exception {
		JsonNode user = eligibleUser();
		Device device = trustedDevice();
		JsonNode challenge = issue(user);
		UUID key = UUID.randomUUID();
		RegisterKeyringRequest body = dto(challenge, device);
		long userId = userIdOf(user);
		UUID family = familyOf(user);
		assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			service.register(userId, family, key, body);
			throw new IllegalStateException("synthetic rollback");
		})).isInstanceOf(IllegalStateException.class).hasMessage("synthetic rollback");
		assertThat(owner(device)).isNull();
		assertThat(generation(device)).isNull();
		assertThat(consumed(challenge)).isFalse();
		register(user, key, toJson(body));
	}

	@Test
	void replayRejectsChangedOwnerOrGenerationAndRevokedCredential() throws Exception {
		JsonNode user = eligibleUser();
		JsonNode other = eligibleUser();
		Device device = trustedDevice();
		String body = request(issue(user), device);
		UUID key = UUID.randomUUID();
		register(user, key, body);
		UUID originalGeneration = generation(device);
		jdbc.update("update keyring set owner_user_id=? where id=?", userIdOf(other), device.id());
		expectError(user, key, body, 409, "DEVICE_ALREADY_BOUND");
		jdbc.update("update keyring set owner_user_id=?,owner_generation=? where id=?",
			userIdOf(user), UUID.randomUUID(), device.id());
		expectError(user, key, body, 409, "DEVICE_ALREADY_BOUND");
		jdbc.update("update keyring set owner_generation=?,credential_revoked_at=now() where id=?",
			originalGeneration, device.id());
		expectError(user, key, body, 422, "INVALID_DEVICE_PROOF");
	}

	@Test
	void newChallengeCannotRegisterAnAlreadyBoundDeviceEvenToSameOwner() throws Exception {
		JsonNode user = eligibleUser();
		JsonNode other = eligibleUser();
		Device device = trustedDevice();
		register(user, UUID.randomUUID(), request(issue(user), device));
		UUID originalGeneration = generation(device);
		for (JsonNode caller : List.of(user, other)) {
			JsonNode challenge = issue(caller);
			expectError(caller, UUID.randomUUID(), request(challenge, device), 409, "DEVICE_ALREADY_BOUND");
			assertThat(consumed(challenge)).isFalse();
		}
		assertThat(generation(device)).isEqualTo(originalGeneration);
		assertThat(owner(device)).isEqualTo(userIdOf(user));
	}

	@Test
	void secondDeviceRegistrationPreservesOtherDeviceAcknowledgementAndSettings() throws Exception {
		JsonNode user = eligibleUser();
		long owner = userIdOf(user);
		Device first = trustedDevice();
		register(user, UUID.randomUUID(), request(issue(user), first));
		jdbc.update("update user_setting set settings_version=7 where user_id=?", owner);
		jdbc.update("update keyring set synced_settings_version=7,synced_at=now() where id=?", first.id());
		Map<String, Object> original = jdbc.queryForMap("select synced_settings_version,synced_at,owner_generation "
			+ "from keyring where id=?", first.id());
		Device second = trustedDevice();
		register(user, UUID.randomUUID(), request(issue(user), second));
		assertThat(jdbc.queryForMap("select synced_settings_version,synced_at,owner_generation "
			+ "from keyring where id=?", first.id())).isEqualTo(original);
		assertThat(jdbc.queryForObject("select settings_version from user_setting where user_id=?",
			Long.class, owner)).isEqualTo(7);
		assertThat(jdbc.queryForMap("select synced_settings_version,synced_at from keyring where id=?",
			second.id()).values()).containsOnlyNulls();
	}

	private JsonNode eligibleUser() throws Exception {
		JsonNode user = login("kakao", newSocialId());
		agree(user).andExpect(status().isOk());
		return user;
	}

	private Instant fixedNow() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		given(clock.instant()).willReturn(now);
		return now;
	}

	private Device trustedDevice() throws Exception {
		KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		String serial = "test-" + newSocialId();
		String keyId = "key-" + newSocialId();
		String publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(keys.getPublic().getEncoded());
		long id = jdbc.queryForObject("insert into keyring(serial_number,firmware_version,state,"
			+ "credential_key_id,credential_public_key) values (?,'test-fw','UNBOUND',?,?) returning id",
			Long.class, serial, keyId, publicKey);
		return new Device(id, serial, keyId, keys);
	}

	private JsonNode issue(JsonNode user) throws Exception {
		String body = perform(post(PATH + "/registration-challenges").header("Idempotency-Key", UUID.randomUUID()),
			user, null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return jsonMapper.readTree(body);
	}

	private RegistrationDeviceProof signedProof(JsonNode challenge, Device device) throws Exception {
		Signature signer = Signature.getInstance("Ed25519");
		signer.initSign(device.keys().getPrivate());
		signer.update(KeyringProofTranscript.registration(device.keyId(), device.serial(), challengeId(challenge),
			challenge.path("nonce").asString(), Instant.parse(challenge.path("expiresAt").asString())
				.getEpochSecond()).bytes());
		String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
		return new RegistrationDeviceProof("tingle-device-proof-v1", device.keyId(), signature);
	}

	private RegisterKeyringRequest dto(JsonNode challenge, Device device) throws Exception {
		return new RegisterKeyringRequest(challenge.path("challengeId").asString(), device.serial(),
			signedProof(challenge, device));
	}

	private String request(JsonNode challenge, Device device) throws Exception {
		return toJson(dto(challenge, device));
	}

	private ResultActions call(JsonNode user, UUID key, String body) throws Exception {
		return perform(post(PATH).header("Idempotency-Key", key), user, body);
	}

	private JsonNode register(JsonNode user, UUID key, String body) throws Exception {
		String response = call(user, key, body).andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", "no-store")).andExpect(header().exists("X-Request-Id"))
			.andReturn().getResponse().getContentAsString();
		assertThat(response).doesNotContain("nonce", "signature", "publicKey", "ownerGeneration", "proofHash");
		return jsonMapper.readTree(response);
	}

	private void expectError(JsonNode user, UUID key, String body, int expected, String code) throws Exception {
		ResultActions result = call(user, key, body).andExpect(status().is(expected))
			.andExpect(header().string("Cache-Control", "no-store")).andExpect(header().exists("X-Request-Id"));
		if (code != null) {
			result.andExpect(jsonPath("$.code").value(code));
		}
	}

	private UUID challengeId(JsonNode challenge) {
		return UUID.fromString(challenge.path("challengeId").asString());
	}

	private boolean consumed(JsonNode challenge) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select consumed_at is not null "
			+ "from registration_challenge where challenge_id=?", Boolean.class, challengeId(challenge)));
	}

	private Long owner(Device device) {
		return jdbc.queryForObject("select owner_user_id from keyring where id=?", Long.class, device.id());
	}

	private UUID generation(Device device) {
		return jdbc.queryForObject("select owner_generation from keyring where id=?", UUID.class, device.id());
	}

	private UUID familyOf(JsonNode user) throws Exception {
		String payload = user.path("accessToken").asString().split("\\.")[1];
		return UUID.fromString(jsonMapper.readTree(Base64.getUrlDecoder().decode(payload)).path("sid").asString());
	}

	private record Device(long id, String serial, String keyId, KeyPair keys) {
	}
}
