package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.keyring.proof.PeerCommandTranscripts;

import tools.jackson.databind.JsonNode;

/** Isolated PostgreSQL and synthetic Ed25519 keys; no live keyring or team signing key. */
@SpringBootTest(properties = {"app.keyring.peer.enabled=true",
	"app.keyring.peer.device-serials=XIAO-a000000000000001,XIAO-a000000000000002,"
		+ "XIAO-a000000000000003,XIAO-a000000000000004,XIAO-a000000000000005,XIAO-a000000000000006",
	"spring.sql.init.schema-locations=classpath:database/schema.sql,"
		+ "classpath:database/keyring-peer-ownership-270.sql,classpath:database/keyring-peer-commands-270.sql"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import({TestcontainersConfiguration.class, DevSettingsIntegrationTest.SigningConfiguration.class})
class PeerOwnerSessionIntegrationTest extends IntegrationTestSupport {

	@Autowired
	private KeyPair settingsServerKeys;
	@Autowired
	private PeerOwnerSessionService service;

	@BeforeEach
	void freezeWholeSeconds() {
		given(clock.instant()).willReturn(Instant.now().truncatedTo(ChronoUnit.SECONDS));
		// The pin belongs to the isolated synthetic server key, never the team firmware key.
		ReflectionTestUtils.setField(service, "expectedServerPublicKey",
			HexFormat.of().formatHex(settingsServerKeys.getPublic().getEncoded(), 12, 44));
	}

	@Test
	void freezesScopedGrantAndRecoversExactResponseWithIndependentSignatureVerification() throws Exception {
		Fixture fixture = fixture(1, true);
		UUID key = UUID.randomUUID();
		JsonNode issued = create(fixture, key, request(fixture, nonce(1), 5, 120), 200);
		assertThat(issued.path("proofProtocolVersion").asString()).isEqualTo("tingle-owner-session-v2");
		assertThat(issued.path("deviceId").asLong()).isEqualTo(fixture.deviceId());
		assertThat(issued.path("permissions").asInt()).isEqualTo(5);
		Instant time = Instant.parse(issued.path("serverTime").asString());
		assertThat(issued.path("expiresAtEpochSeconds").asLong()).isEqualTo(time.plusSeconds(120).getEpochSecond());
		var binding = new PeerCommandTranscripts.Binding(issued.path("serverKeyId").asString(), fixture.serial(),
			fixture.keyId(), fixture.deviceId(), fixture.generation(), 2);
		var verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(settingsServerKeys.getPublic());
		verifier.update(PeerCommandTranscripts.owner(binding, nonce(1), 5, time.getEpochSecond(),
			issued.path("expiresAtEpochSeconds").asLong()));
		assertThat(verifier.verify(Base64.getUrlDecoder().decode(issued.path("serverSignature").asString()))).isTrue();
		given(clock.instant()).willReturn(time.plusSeconds(10));
		assertThat(create(fixture, key, request(fixture, nonce(1), 5, 120), 200)).isEqualTo(issued);
		JsonNode recovered = jsonMapper.readTree(perform(get(path(fixture) + "/" + key), fixture.tokens(), null)
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andReturn().getResponse().getContentAsString());
		assertThat(recovered).isEqualTo(issued);
		assertThat(jdbc.queryForObject("select count(*) from keyring_peer_owner_grant where keyring_id=?",
			Long.class, fixture.deviceId())).isEqualTo(1);
	}

	@Test
	void rejectsChangedChallengePermissionsAndLifetimeOnTheSameIdempotencyKey() throws Exception {
		Fixture fixture = fixture(2, true);
		UUID key = UUID.randomUUID();
		create(fixture, key, request(fixture, nonce(1), 1, 60), 200);
		create(fixture, key, request(fixture, nonce(2), 1, 60), 409);
		create(fixture, key, request(fixture, nonce(1), 2, 60), 409);
		create(fixture, key, request(fixture, nonce(1), 1, 61), 409);
	}

	@Test
	void expirationNeverReissuesOrExtendsAnExistingGrant() throws Exception {
		Fixture fixture = fixture(3, true);
		UUID key = UUID.randomUUID();
		create(fixture, key, request(fixture, nonce(1), 15, 300), 200);
		Instant expired = clock.instant().plusSeconds(300);
		given(clock.instant()).willReturn(expired);
		create(fixture, key, request(fixture, nonce(1), 15, 300), 410);
		perform(get(path(fixture) + "/" + key), fixture.tokens(), null).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("OWNER_PROOF_EXPIRED"));
		assertThat(jdbc.queryForObject("select count(*) from keyring_peer_owner_grant where keyring_id=?",
			Long.class, fixture.deviceId())).isEqualTo(1);
	}

	@Test
	void rejectsOtherOwnerWrongEpochMissingEpochAndExcessAuthority() throws Exception {
		Fixture fixture = fixture(4, true);
		Map<String, Object> request = new LinkedHashMap<>(request(fixture, nonce(1), 1, 60));
		request.put("ownershipEpoch", 3);
		create(fixture, UUID.randomUUID(), request, 409);
		request.remove("ownershipEpoch");
		create(fixture, UUID.randomUUID(), request, 400);
		create(fixture, UUID.randomUUID(), request(fixture, nonce(1), 16, 60), 400);
		create(fixture, UUID.randomUUID(), request(fixture, nonce(1), 1, 301), 400);
		JsonNode other = login("kakao", newSocialId());
		agree(other).andExpect(status().isOk());
		perform(post(path(fixture)).header("Idempotency-Key", UUID.randomUUID()), other,
			toJson(request(fixture, nonce(1), 1, 60))).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("OWNER_MISMATCH"));
	}

	@Test
	void requiresAgreementButDoesNotRequireProfileOrCompleteOnboarding() throws Exception {
		Fixture fixture = fixture(5, false);
		create(fixture, UUID.randomUUID(), request(fixture, nonce(1), 1, 60), 403);
		agree(fixture.tokens()).andExpect(status().isOk());
		create(fixture, UUID.randomUUID(), request(fixture, nonce(1), 1, 60), 200);
		assertThat(jdbc.queryForObject("select signup_completed_at from users where id=?", Instant.class,
			userIdOf(fixture.tokens()))).isNull();
	}

	@Test
	void revokedLoginFamilyCannotRecoverOrIssueOwnerGrants() throws Exception {
		Fixture fixture = fixture(6, true);
		UUID key = UUID.randomUUID();
		create(fixture, key, request(fixture, nonce(1), 1, 60), 200);
		logout(fixture.tokens().path("refreshToken").asString()).andExpect(status().isNoContent());
		create(fixture, UUID.randomUUID(), request(fixture, nonce(2), 1, 60), 401);
		perform(get(path(fixture) + "/" + key), fixture.tokens(), null).andExpect(status().isUnauthorized());
	}

	private Fixture fixture(int serialNumber, boolean agreed) throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		if (agreed) {
			agree(tokens).andExpect(status().isOk());
		}
		KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		String keyId = "ed25519-" + HexFormat.of().formatHex(keys.getPublic().getEncoded(), 12, 20);
		String serial = "XIAO-a00000000000000" + serialNumber;
		UUID generation = UUID.randomUUID();
		long id = jdbc.queryForObject("insert into keyring(serial_number,firmware_version,state,owner_user_id,"
			+ "owner_generation,ownership_epoch,credential_key_id,credential_public_key) "
			+ "values (?,'synthetic','REGISTERED',?,?,2,?,?) returning id", Long.class, serial, userIdOf(tokens),
			generation, keyId, Base64.getUrlEncoder().withoutPadding().encodeToString(keys.getPublic().getEncoded()));
		return new Fixture(tokens, id, generation, serial, keyId);
	}

	private JsonNode create(Fixture fixture, UUID key, Map<String, Object> request, int expected) throws Exception {
		String response = perform(post(path(fixture)).header("Idempotency-Key", key), fixture.tokens(), toJson(request))
			.andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
		return jsonMapper.readTree(response);
	}

	private static Map<String, Object> request(Fixture fixture, String nonce, int permissions, int lifetime) {
		return Map.of("ownerGeneration", fixture.generation(), "ownershipEpoch", 2, "deviceNonce", nonce,
			"permissions", permissions, "lifetimeSeconds", lifetime);
	}

	private static String nonce(int value) {
		byte[] bytes = new byte[32];
		Arrays.fill(bytes, (byte)value);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String path(Fixture fixture) {
		return "/api/keyrings/" + fixture.deviceId() + "/owner-session-grants";
	}

	private record Fixture(JsonNode tokens, long deviceId, UUID generation, String serial, String keyId) {
	}
}
