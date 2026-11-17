package com.e106.tingle.keyring.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.keyring.KeyringRegistrationRepository;
import com.e106.tingle.keyring.proof.KeyringProofTranscript;
import com.e106.tingle.keyring.reset.ResetDeviceProtocol;
import com.e106.tingle.user.UserRepository;

import tools.jackson.databind.JsonNode;

/** Disposable PostgreSQL; reset transport is synthetic, registration signatures use isolated JCA keys. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class PeerOwnershipIntegrationTest extends IntegrationTestSupport {

	@Autowired
	private PeerOwnershipService ownership;
	@Autowired
	private KeyringRegistrationRepository devices;
	@Autowired
	private UserRepository users;
	@Autowired
	private PlatformTransactionManager transactions;
	@MockitoBean
	private ResetDeviceProtocol reset;

	@BeforeEach
	void syntheticResetProtocol() {
		given(reset.issue(any())).willReturn("synthetic-owner-history-reset");
		given(reset.verify(any(), anyString())).willAnswer(call -> "synthetic-cleared".equals(call.getArgument(1)));
	}

	@Test
	void sharesOneOpaqueOwnerAcrossConcurrentDevicesButNotDifferentAccounts() throws Exception {
		long user = userIdOf(login("kakao", newSocialId()));
		long first = device(user, 0L);
		long second = device(user, 0L);
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		var scopes = runConcurrently(() -> current(user, first, now), () -> current(user, second, now));
		assertThat(scopes.getFirst().ownerRef()).isEqualTo(scopes.getLast().ownerRef());
		assertThat(current(user, first, now)).isEqualTo(scopes.getFirst());
		long other = userIdOf(login("kakao", newSocialId()));
		assertThat(current(other, device(other, 0L), now).ownerRef()).isNotEqualTo(scopes.getFirst().ownerRef());
		assertThat(jdbc.queryForObject("select count(*) from keyring_peer_owner where user_id=?", Long.class, user))
			.isEqualTo(1);
	}

	@Test
	void observesLegacyOwnershipNowAndResolvesOnlyItsFrozenHistoricalInterval() throws Exception {
		long user = userIdOf(login("kakao", newSocialId()));
		long device = device(user, 0L);
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		var scope = current(user, device, now);
		assertThat(ownership.findAt(device, scope.ownerGeneration(), 0, now.minusSeconds(1))).isEmpty();
		assertThat(ownership.findAt(scope.serial(), scope.ownerGeneration(), 0, now)).contains(scope);
		new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			users.findByIdForUpdate(user).orElseThrow();
			ownership.close(devices.findByIdForUpdate(device).orElseThrow(), now.plusSeconds(10));
		});
		assertThat(ownership.findAt(device, scope.ownerGeneration(), 0, now.plusSeconds(9))).isPresent();
		assertThat(ownership.findAt(device, scope.ownerGeneration(), 0, now.plusSeconds(10))).isEmpty();
		assertThat(ownership.findAt(device, scope.ownerGeneration(), 1, now)).isEmpty();
		assertThatThrownBy(() -> current(user, device, now.plusSeconds(10))).isInstanceOf(BusinessException.class);
	}

	@Test
	void rollbackLeavesNoInventedOwnerRefOrOwnershipEvidence() throws Exception {
		long user = userIdOf(login("kakao", newSocialId()));
		long device = device(user, 0L);
		assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			users.findByIdForUpdate(user).orElseThrow();
			ownership.current(devices.findByIdForUpdate(device).orElseThrow(), Instant.now());
			throw new IllegalStateException("synthetic rollback");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(jdbc.queryForObject("select count(*) from keyring_peer_owner where user_id=?", Long.class, user))
			.isZero();
		assertThat(jdbc.queryForObject("select count(*) from keyring_peer_ownership where keyring_id=?",
			Long.class, device)).isZero();
	}

	@Test
	void unknownEpochAndChangedCredentialNeverRewriteHistory() throws Exception {
		long user = userIdOf(login("kakao", newSocialId()));
		long unknown = device(user, null);
		assertThatThrownBy(() -> current(user, unknown, Instant.now())).isInstanceOf(BusinessException.class);
		long device = device(user, 0L);
		var scope = current(user, device, Instant.now());
		jdbc.update("update keyring set credential_public_key='changed-key' where id=?", device);
		assertThatThrownBy(() -> current(user, device, Instant.now())).isInstanceOf(BusinessException.class);
		assertThat(ownership.findAt(device, scope.ownerGeneration(), 0, scope.validFrom()).orElseThrow().publicKey())
			.isEqualTo(scope.publicKey());
	}

	@Test
	void signedV2RegistrationCapturesHistoryAndOldResetRetryCannotCloseNewOwner() throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		given(clock.instant()).willReturn(now);
		KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		String serial = "owner-test-" + newSocialId();
		String keyId = "owner-key-" + newSocialId();
		long device = jdbc.queryForObject("insert into keyring(serial_number,firmware_version,state,"
			+ "credential_key_id,credential_public_key) values (?,'test','UNBOUND',?,?) returning id", Long.class,
			serial, keyId, encode(keys.getPublic().getEncoded()));
		register(tokens, keys, serial, keyId, 0);
		var old = current(userIdOf(tokens), device, now);
		JsonNode run = jsonMapper.readTree(perform(post("/api/keyrings/" + device + "/reset-runs")
			.header("Idempotency-Key", UUID.randomUUID()), tokens, "{\"discardUnsentConfirmed\":false}")
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		String ackPath = "/api/keyrings/" + device + "/reset-runs/" + run.path("resetRunId").asString() + "/ack";
		given(clock.instant()).willReturn(now.plusSeconds(10));
		perform(post(ackPath), tokens, "{\"proof\":\"synthetic-cleared\"}").andExpect(status().isOk());
		JsonNode next = login("kakao", newSocialId());
		agree(next).andExpect(status().isOk());
		register(next, keys, serial, keyId, 1);
		var replacement = current(userIdOf(next), device, now.plusSeconds(10));
		assertThat(replacement.ownerRef()).isNotEqualTo(old.ownerRef());
		perform(post(ackPath), tokens, "{\"proof\":\"synthetic-cleared\"}").andExpect(status().isOk());
		assertThat(current(userIdOf(next), device, now.plusSeconds(10))).isEqualTo(replacement);
		assertThat(ownership.findAt(device, old.ownerGeneration(), 0, now.plusSeconds(9))).isPresent();
		assertThat(ownership.findAt(device, old.ownerGeneration(), 0, now.plusSeconds(10))).isEmpty();
	}

	private PeerOwnershipService.Scope current(long user, long device, Instant now) {
		return new TransactionTemplate(transactions).execute(transaction -> {
			users.findByIdForUpdate(user).orElseThrow();
			return ownership.current(devices.findByIdForUpdate(device).orElseThrow(), now);
		});
	}

	private long device(long user, Long epoch) {
		return jdbc.queryForObject("insert into keyring(serial_number,firmware_version,state,owner_user_id,"
			+ "owner_generation,ownership_epoch,credential_key_id,credential_public_key) "
			+ "values (?,'synthetic','REGISTERED',?,?,?,'synthetic-key','synthetic-public') returning id", Long.class,
			"owner-" + UUID.randomUUID(), user, UUID.randomUUID(), epoch);
	}

	private void register(JsonNode tokens, KeyPair keys, String serial, String keyId, long epoch) throws Exception {
		JsonNode challenge = jsonMapper.readTree(perform(post("/api/keyrings/registration-challenges")
			.header("Idempotency-Key", UUID.randomUUID()), tokens, null).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		var signer = Signature.getInstance("Ed25519");
		signer.initSign(keys.getPrivate());
		signer.update(KeyringProofTranscript.registrationV2(keyId, serial,
			UUID.fromString(challenge.path("challengeId").asString()), challenge.path("nonce").asString(),
			Instant.parse(challenge.path("expiresAt").asString()).getEpochSecond(), epoch).bytes());
		String request = toJson(Map.of("challengeId", challenge.path("challengeId").asString(), "deviceSerial", serial,
			"ownershipEpoch", epoch, "ownerState", "UNOWNED", "proof", Map.of("protocolVersion",
				"tingle-device-proof-v2", "keyId", keyId, "signature", encode(signer.sign()))));
		perform(post("/api/keyrings").header("Idempotency-Key", UUID.randomUUID()), tokens, request)
			.andExpect(status().isCreated());
	}

	private static String encode(byte[] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}
}
