package com.e106.tingle.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.sql.Timestamp;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.encounter.peer.Ter2ReceiptService;
import com.e106.tingle.encounter.peer.Ter2ReceiptService.Stored;
import com.e106.tingle.keyring.proof.PeerCommandTranscripts;
import com.e106.tingle.keyring.proof.PeerCommandTranscripts.Binding;

import tools.jackson.databind.JsonNode;

/** Real PostgreSQL/API policy checks. Only the already-verified TER2 source lookup is mocked. */
@SpringBootTest(properties = {"app.keyring.peer.enabled=true", "app.keyring.peer.encounters-enabled=true",
	"spring.sql.init.schema-locations=classpath:database/schema.sql,"
		+ "classpath:database/keyring-peer-ownership-270.sql,classpath:database/keyring-peer-commands-270.sql,"
		+ "classpath:database/keyring-peer-encounter-270.sql"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "erd"})
@Import({TestcontainersConfiguration.class, DevSettingsIntegrationTest.SigningConfiguration.class})
class PeerRecordCommandIntegrationTest extends IntegrationTestSupport {

	@Autowired
	private PeerRecordCommandService service;
	@Autowired
	private KeyPair settingsServerKeys;
	@MockitoBean
	private Ter2ReceiptService receipts;
	private Instant now;

	@BeforeEach
	void syntheticKeyAndExplicitPolicyGate() {
		now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		given(clock.instant()).willReturn(now);
		ReflectionTestUtils.setField(service, "expectedServerPublicKey",
			HexFormat.of().formatHex(settingsServerKeys.getPublic().getEncoded(), 12, 44));
		ReflectionTestUtils.setField(service, "releasePolicyApproved", false);
	}

	@Test
	void restoresExactFrozenCredentialAndRecoversIdenticalSignedCommandWithoutExtendingTime() throws Exception {
		Fixture fixture = fixture("VERIFIED_BOTH");
		UUID key = UUID.randomUUID();
		JsonNode first = command(fixture, key, "RESTORE_ACCOUNT_LOCK", 200);
		assertThat(decode(first.path("peerCredential").asString())).isEqualTo(fixture.source().peerCredential());
		assertThat(first.path("proofProtocolVersion").asString()).isEqualTo("tingle-account-lock-v2");
		assertThat(first.path("receivedAtEpochSeconds").isNull()).isTrue();
		assertThat(first.path("result").isNull()).isTrue();
		verify(fixture, first);
		given(clock.instant()).willReturn(now.plusSeconds(10));
		assertThat(command(fixture, key, "RESTORE_ACCOUNT_LOCK", 200)).isEqualTo(first);
		JsonNode recovered = jsonMapper.readTree(perform(get(path(fixture) + "/" + key), fixture.tokens(), null)
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(recovered).isEqualTo(first);
		command(fixture, key, "RELEASE_AFTER_DEPARTURE", 409);
	}

	@Test
	void expiredRestoreNeedsANewKeyAndNeverExtendsTheOldGrant() throws Exception {
		Fixture fixture = fixture("VERIFIED_BOTH");
		UUID key = UUID.randomUUID();
		JsonNode first = command(fixture, key, "RESTORE_ACCOUNT_LOCK", 200);
		given(clock.instant()).willReturn(now.plusSeconds(301));
		command(fixture, key, "RESTORE_ACCOUNT_LOCK", 410);
		perform(get(path(fixture) + "/" + key), fixture.tokens(), null).andExpect(status().isGone());
		JsonNode replacement = command(fixture, UUID.randomUUID(), "RESTORE_ACCOUNT_LOCK", 200);
		assertThat(replacement.path("grantId")).isNotEqualTo(first.path("grantId"));
		assertThat(replacement.path("issuedAtEpochSeconds").asLong()).isEqualTo(now.plusSeconds(301).getEpochSecond());
	}

	@Test
	void oldReceiptCommandCannotCrossCurrentOwnerGenerationOrRetiredRawEvidence() throws Exception {
		Fixture fixture = fixture("VERIFIED_BOTH");
		UUID key = UUID.randomUUID();
		command(fixture, key, "RESTORE_ACCOUNT_LOCK", 200);
		jdbc.update("update keyring set owner_generation=? where id=?", UUID.randomUUID(), fixture.device());
		command(fixture, key, "RESTORE_ACCOUNT_LOCK", 409);
		jdbc.update("update keyring set owner_generation=? where id=?", fixture.generation(), fixture.device());
		// The mocked lookup still returns a usable source; the separate durable-row lock must reject retirement.
		jdbc.update("update keyring_peer_encounter set retired=true,raw_payload=null,receipt_json=null "
			+ "where receipt_id=?", fixture.receipt());
		command(fixture, key, "RESTORE_ACCOUNT_LOCK", 409);
	}

	@Test
	void departureReleaseRequiresApprovedPolicyBilateralProofAndTheActualNotMatchedRelation() throws Exception {
		Fixture fixture = fixture("WAITING_COUNTERPART");
		UUID key = UUID.randomUUID();
		command(fixture, key, "RELEASE_AFTER_DEPARTURE", 422);
		ReflectionTestUtils.setField(service, "releasePolicyApproved", true);
		command(fixture, key, "RELEASE_AFTER_DEPARTURE", 422);
		stub(fixture, "VERIFIED_BOTH", fixture.source().rawPayload());
		command(fixture, key, "RELEASE_AFTER_DEPARTURE", 422);
		relation(fixture);
		JsonNode release = command(fixture, key, "RELEASE_AFTER_DEPARTURE", 200);
		assertThat(release.path("proofProtocolVersion").asString()).isEqualTo("tingle-record-release-v2");
		assertThat(release.path("peerCredential").isNull()).isTrue();
		assertThat(release.path("grantId").isNull()).isTrue();
		assertThat(release.path("issuedAtEpochSeconds").isNull()).isTrue();
		verify(fixture, release);
	}

	@Test
	void blocksProposalOrChatEvidenceCannotBeBypassedByReleaseSigning() throws Exception {
		Fixture fixture = fixture("VERIFIED_BOTH");
		ReflectionTestUtils.setField(service, "releasePolicyApproved", true);
		long relation = relation(fixture);
		jdbc.update("insert into user_block(id,owner_id,target_id,matching_id,created_at) values (?,?,?,?,?)",
			UUID.randomUUID(), fixture.user(), fixture.peer(), relation, Timestamp.from(now));
		command(fixture, UUID.randomUUID(), "RELEASE_AFTER_DEPARTURE", 422);
		jdbc.update("delete from user_block where matching_id=?", relation);
		jdbc.update("update matching set proposed_at=? where id=?", Timestamp.from(now), relation);
		command(fixture, UUID.randomUUID(), "RELEASE_AFTER_DEPARTURE", 422);
		jdbc.update("update matching set proposed_at=null where id=?", relation);
		jdbc.update("insert into chat(matching_id,created_at,expires_at,last_message_seq,icebreakers,ai_state) "
			+ "values (?,?,?,0,'[]'::jsonb,'{}'::jsonb)", relation, Timestamp.from(now),
			Timestamp.from(now.plusSeconds(86400)));
		command(fixture, UUID.randomUUID(), "RELEASE_AFTER_DEPARTURE", 422);
	}

	@Test
	void errorReleaseRequiresAnActualReviewResolutionAndErrorFingerprint() throws Exception {
		Fixture fixture = fixture("REVIEW_QUARANTINED");
		ReflectionTestUtils.setField(service, "releasePolicyApproved", true);
		byte[] reviewed = fixture.source().rawPayload();
		reviewed[55] |= (byte)0x80;
		stub(fixture, "REVIEW_QUARANTINED", reviewed);
		UUID key = UUID.randomUUID();
		command(fixture, key, "ERROR_REVIEWED", 422);
		jdbc.update("insert into keyring_peer_review_resolution(receipt_id,reviewed_at,disposition) "
			+ "values (?,?,'ERROR_REVIEWED')", fixture.receipt(), Timestamp.from(now));
		JsonNode result = command(fixture, key, "ERROR_REVIEWED", 200);
		assertThat(result.path("result").asString()).isEqualTo("ERROR_REVIEWED");
		verify(fixture, result);
	}

	@Test
	void refusesNonXiaoSerialAndRevokedOrMissingCredential() throws Exception {
		Fixture fixture = fixture("VERIFIED_BOTH");
		String key = jdbc.queryForObject("select credential_public_key from keyring where id=?", String.class,
			fixture.device());
		for (String broken : new String[] {"serial_number='NRF-" + fixture.serial().substring(5) + "'",
			"credential_revoked_at=now()", "credential_public_key=null", "credential_key_id=null"}) {
			jdbc.update("update keyring set " + broken + " where id=?", fixture.device());
			assertThat(command(fixture, UUID.randomUUID(), "RESTORE_ACCOUNT_LOCK", 409).path("code").asString())
				.isEqualTo("OWNER_MISMATCH");
			jdbc.update("update keyring set serial_number=?,credential_revoked_at=null,credential_public_key=?,"
				+ "credential_key_id=? where id=?", fixture.serial(), key, fixture.keyId(), fixture.device());
		}
		command(fixture, UUID.randomUUID(), "RESTORE_ACCOUNT_LOCK", 200);
	}

	private Fixture fixture(String state) throws Exception {
		JsonNode tokens = login("kakao", newSocialId());
		agree(tokens).andExpect(status().isOk());
		long user = userIdOf(tokens);
		long peer = userIdOf(login("kakao", newSocialId()));
		String serial = "XIAO-" + HexFormat.of().toHexDigits(user);
		KeyPair deviceKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		String keyId = "ed25519-" + HexFormat.of().formatHex(deviceKeys.getPublic().getEncoded(), 12, 20);
		String publicKey = encode(deviceKeys.getPublic().getEncoded());
		UUID generation = UUID.randomUUID();
		long device = jdbc.queryForObject("insert into keyring(serial_number,firmware_version,state,owner_user_id,"
			+ "owner_generation,ownership_epoch,credential_key_id,credential_public_key) "
			+ "values (?,'synthetic','REGISTERED',?,?,2,?,?) returning id", Long.class, serial, user, generation,
			keyId, publicKey);
		jdbc.update("insert into keyring_peer_owner(user_id,owner_ref,created_at) values (?,?,?)", user,
			UUID.randomUUID(), Timestamp.from(now.minusSeconds(120)));
		UUID ownerRef = jdbc.queryForObject("select owner_ref from keyring_peer_owner where user_id=?",
			UUID.class, user);
		jdbc.update("insert into keyring_peer_ownership(keyring_id,owner_generation,ownership_epoch,owner_user_id,"
			+ "owner_ref,device_serial,credential_key_id,credential_public_key,valid_from) values (?,?,2,?,?,?,?,?,?)",
			device, generation, user, ownerRef, serial, keyId, publicKey, Timestamp.from(now.minusSeconds(120)));
		byte[] raw;
		try (var input = getClass().getResourceAsStream("/keyring/app-flow-v3-vectors.json")) {
			raw = decode(jsonMapper.readTree(input).path("vectors").path("encounter").path("base64url").asString());
		}
		int evidence = 24 + Short.toUnsignedInt(ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getShort(4));
		byte[] credential = Arrays.copyOfRange(raw, evidence + 437, evidence + 794);
		UUID receipt = UUID.randomUUID();
		Stored source = new Stored(receipt, user, peer, device, generation, 2, UUID.randomUUID(), UUID.randomUUID(),
			hash(raw), now.minusSeconds(30), now.minusSeconds(60), now.plusSeconds(86400), state, raw, credential,
			"{\"synthetic\":true}", false);
		jdbc.update("insert into keyring_peer_encounter(receipt_id,owner_user_id,peer_user_id,device_id,"
			+ "owner_generation,ownership_epoch,event_id,submission_id,received_at,occurred_at,expires_at,"
			+ "encounter_status,raw_payload,envelope_hash,peer_credential,receipt_json) "
			+ "values (?,?,?,?,?,2,?,?,?,?,?,?,?,?,?,?)", receipt, user, peer, device, generation, source.eventId(),
			source.submissionId(), Timestamp.from(source.receivedAt()), Timestamp.from(source.occurredAt()),
			Timestamp.from(source.expiresAt()), state, raw, source.envelopeHash(), credential, source.receiptJson());
		Fixture fixture = new Fixture(tokens, user, peer, device, generation, serial, keyId, receipt, source);
		stub(fixture, state, raw);
		return fixture;
	}

	private void stub(Fixture fixture, String state, byte[] raw) {
		Stored source = fixture.source();
		given(receipts.lookup(fixture.user(), fixture.device(), fixture.receipt())).willReturn(new Stored(
			source.receiptId(), source.ownerUserId(), source.peerUserId(), source.deviceId(), source.ownerGeneration(),
			source.ownershipEpoch(), source.eventId(), source.submissionId(), source.envelopeHash(),
			source.receivedAt(), source.occurredAt(), source.expiresAt(), state, raw, source.peerCredential(),
			source.receiptJson(), false));
	}

	private long relation(Fixture fixture) {
		return jdbc.queryForObject("insert into matching(user_low_id,user_high_id,met_at,status) "
			+ "values (?,?,?,'NOT_MATCHED') returning id", Long.class, Math.min(fixture.user(), fixture.peer()),
			Math.max(fixture.user(), fixture.peer()), Timestamp.from(fixture.source().occurredAt()));
	}

	private JsonNode command(Fixture fixture, UUID key, String command, int expected) throws Exception {
		return jsonMapper.readTree(perform(post(path(fixture)).header("Idempotency-Key", key), fixture.tokens(),
			toJson(Map.of("receiptId", fixture.receipt(), "command", command))).andExpect(status().is(expected))
			.andReturn().getResponse().getContentAsString());
	}

	private void verify(Fixture fixture, JsonNode result) throws Exception {
		Binding binding = new Binding(result.path("serverKeyId").asString(), fixture.serial(), fixture.keyId(),
			fixture.device(), fixture.generation(), 2);
		byte[] canonical = "tingle-account-lock-v2".equals(result.path("proofProtocolVersion").asString())
			? PeerCommandTranscripts.restore(binding, fixture.source().eventId(),
				result.path("peerDeviceRefHex").asString(), result.path("ownerRefHex").asString(),
				UUID.fromString(result.path("grantId").asString()), result.path("issuedAtEpochSeconds").asLong(),
				hash(decode(result.path("peerCredential").asString())))
			: PeerCommandTranscripts.release(binding, fixture.source().eventId(),
				result.path("peerDeviceRefHex").asString(), fixture.receipt(),
				result.path("receivedAtEpochSeconds").asLong(), result.path("result").asString());
		var verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(settingsServerKeys.getPublic());
		verifier.update(canonical);
		assertThat(verifier.verify(decode(result.path("serverSignature").asString()))).isTrue();
	}

	private static String path(Fixture fixture) {
		return "/api/keyrings/" + fixture.device() + "/record-commands";
	}

	private static String hash(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static String encode(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static byte[] decode(String value) {
		return Base64.getUrlDecoder().decode(value);
	}

	private record Fixture(JsonNode tokens, long user, long peer, long device, UUID generation, String serial,
		String keyId, UUID receipt, Stored source) {
	}
}
