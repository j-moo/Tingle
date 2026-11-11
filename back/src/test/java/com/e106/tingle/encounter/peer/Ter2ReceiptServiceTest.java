package com.e106.tingle.encounter.peer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.CRC32;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import com.e106.tingle.SharedPostgres;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.DeviceProof;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;
import com.e106.tingle.encounter.proof.Ter2ProofVerifier;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.DevSettingsIssuer;
import com.e106.tingle.keyring.ownership.PeerOwnershipService;
import com.e106.tingle.keyring.proof.PeerCommandTranscripts;
import com.e106.tingle.keyring.proof.PeerCommandTranscripts.Binding;
import com.e106.tingle.keyring.proof.ServerSettingsSigner;
import com.e106.tingle.settings.snapshot.SettingsSnapshotJson;

import tools.jackson.databind.json.JsonMapper;

/** Isolated disposable PostgreSQL and public synthetic firmware keys; no real accounts, keys or shared DB. */
class Ter2ReceiptServiceTest {

	private static final JdbcConnectionDetails POSTGRES = SharedPostgres.newDatabase();
	private static JdbcTemplate jdbc;
	private static DriverManagerDataSource dataSource;
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final SettingsSnapshotJson snapshots = new SettingsSnapshotJson();
	private Ter2ReceiptService service;
	private DevSettingsIssuer issuer;
	private ServerSettingsSigner signer;
	private byte[] raw;
	private byte[] bundle;
	private byte[] server;
	private Instant metAt;

	@BeforeAll
	static void createIsolatedSchema() {
		dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("create table users(id bigint primary key,status text not null)");
		jdbc.execute("create table keyring(id bigint primary key,owner_user_id bigint,"
			+ "credential_public_key text,credential_revoked_at timestamptz)");
		jdbc.execute("create table settings_snapshot(user_id bigint,settings_version bigint,input_format text,"
			+ "input_json text,input_hash text,captured_at timestamptz,redacted_at timestamptz)");
		jdbc.execute("create table keyring_sync_run(sync_run_id uuid,keyring_id bigint,owner_user_id bigint,"
			+ "owner_generation uuid,ownership_epoch bigint,settings_version bigint,state text,payload_format text,"
			+ "protocol_version text,credential_key_id text,payload bytea,payload_hash text,nonce text,"
			+ "created_at timestamptz,acked_at timestamptz,ack_proof_hash text,ack_request_key uuid)");
		new ResourceDatabasePopulator(new ClassPathResource("database/keyring-peer-ownership-270.sql"),
			new ClassPathResource("database/keyring-peer-encounter-270.sql")).execute(dataSource);
	}

	@BeforeEach
	void exactPublishedEvidenceWithHistoricalRunsAndSeparateSourceHashes() throws Exception {
		jdbc.execute("truncate keyring_peer_encounter,keyring_peer_ownership,keyring_peer_owner,"
			+ "keyring_sync_run,settings_snapshot,keyring,users");
		try (var input = getClass().getResourceAsStream("/keyring/app-flow-v3-vectors.json")) {
			var vectors = mapper.readTree(input).path("vectors");
			raw = decode(vectors.path("encounter").path("base64url").asString());
			bundle = decode(vectors.path("bundle").path("base64url").asString());
			server = decode(vectors.path("serverPublic").path("base64url").asString());
		}
		metAt = Instant.ofEpochSecond(le(raw).getLong(24 + 32));
		byte[] seed = new byte[32];
		for (int index = 0; index < seed.length; index++) {
			seed[index] = (byte)index;
		}
		var factory = KeyFactory.getInstance("Ed25519");
		var privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(join(
			HexFormat.of().parseHex("302e020100300506032b657004220420"), seed)));
		var publicKey = factory.generatePublic(new X509EncodedKeySpec(join(
			HexFormat.of().parseHex("302a300506032b6570032100"), server)));
		signer = new ServerSettingsSigner(privateKey, publicKey);
		issuer = spy(new DevSettingsIssuer(jdbc, signer));
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("issuer", issuer);
		service = new Ter2ReceiptService(jdbc, new PeerOwnershipService(jdbc),
			beans.getBeanProvider(DevSettingsIssuer.class), Clock.fixed(metAt.plusSeconds(86400), ZoneOffset.UTC),
			mapper, snapshots, new DataSourceTransactionManager(dataSource));
		ReflectionTestUtils.setField(service, "expectedServerPublicKeyHex", HexFormat.of().formatHex(server));
		// Explicit synthetic review policy approval; production's absent/default flag remains false.
		ReflectionTestUtils.setField(service, "reviewIntakeApproved", true);
		int evidence = evidence(raw);
		byte[] own = Arrays.copyOfRange(raw, evidence + 80, evidence + 437);
		byte[] peer = Arrays.copyOfRange(raw, evidence + 437, evidence + 794);
		seedHistory(1, own, bundle);
		byte[] peerBundle = bundle.clone();
		System.arraycopy(raw, 24 + 52 + 230 + 14 + 34, peerBundle, 4, 196);
		System.arraycopy(peer, 0, peerBundle, 200, 261);
		seedHistory(2, peer, peerBundle);
	}

	@Test
	void signsOnlyAfterEvidenceCommitAndRecoversTheIdenticalReceiptAfterResponseLoss() throws Exception {
		doAnswer(call -> {
			try (var connection = dataSource.getConnection();
				var statement = connection.createStatement();
				var result = statement.executeQuery("select receipt_json,raw_payload from keyring_peer_encounter")) {
				assertThat(result.next()).isTrue();
				assertThat(result.getString(1)).isNull();
				assertThat(result.getBytes(2)).isEqualTo(raw);
			}
			return signer.sign(call.getArgument(0, byte[].class));
		}).when(issuer).signCanonical(any());
		Submission submission = submission(raw);
		var first = service.accept(1, 100, submission);
		var replay = service.accept(1, 100, submission);
		assertThat(replay).isEqualTo(first);
		assertThat(first.encounterStatus()).isEqualTo("WAITING_COUNTERPART");
		assertThat(jdbc.queryForObject("select count(*) from keyring_peer_encounter", Long.class)).isEqualTo(1);
		var stored = service.lookup(1, 100, first.receiptId());
		assertThat(stored.rawPayload()).isEqualTo(raw);
		var scope = new PeerOwnershipService(jdbc).findAt(100, stored.ownerGeneration(), 1, metAt).orElseThrow();
		byte[] transcript = PeerCommandTranscripts.receipt(new Binding(signer.keyId(), scope.serial(), scope.keyId(),
			100, stored.ownerGeneration(), 1), stored.eventId(), stored.submissionId(), stored.envelopeHash(),
			stored.receiptId(), stored.receivedAt().getEpochSecond());
		var verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(join(
			HexFormat.of().parseHex("302a300506032b6570032100"), server))));
		verifier.update(transcript);
		assertThat(verifier.verify(decode(first.proof().serverSignature()))).isTrue();
	}

	@Test
	void signingFailureKeepsCommittedOriginalAndSameRetryFinishesItsReceipt() throws Exception {
		doThrow(new BusinessException(ErrorCode.POLICY_UNRESOLVED)).doCallRealMethod()
			.when(issuer).signCanonical(any());
		assertThatThrownBy(() -> service.accept(1, 100, submission(raw))).isInstanceOf(BusinessException.class);
		assertThat(jdbc.queryForObject("select raw_payload from keyring_peer_encounter", byte[].class)).isEqualTo(raw);
		assertThat(jdbc.queryForObject("select receipt_json from keyring_peer_encounter", String.class)).isNull();
		var recovered = service.accept(1, 100, submission(raw));
		assertThat(recovered.proof().receiptStatus()).isEqualTo("STORED");
		assertThat(jdbc.queryForObject("select count(*) from keyring_peer_encounter", Long.class)).isEqualTo(1);
	}

	@Test
	void crossChecksBothIndependentOriginalsWithoutCreatingAProposal() throws Exception {
		var first = service.accept(1, 100, submission(raw));
		byte[] other = counterpart(raw);
		var second = service.accept(2, 101, submission(other));
		assertThat(second.encounterStatus()).isEqualTo("VERIFIED_BOTH");
		assertThat(service.lookup(1, 100, first.receiptId()).encounterStatus()).isEqualTo("VERIFIED_BOTH");
		assertThat(jdbc.queryForObject("select count(*) from keyring_peer_encounter", Long.class)).isEqualTo(2);
	}

	@Test
	void oldOwnerCanReplayAfterTransferButNewOwnerCannotClaimOldEvidence() throws Exception {
		var first = service.accept(1, 100, submission(raw));
		jdbc.update("update keyring_peer_ownership set valid_until=? where keyring_id=100",
			Timestamp.from(metAt.plusSeconds(1)));
		jdbc.update("update keyring set owner_user_id=3 where id=100");
		assertThat(service.accept(1, 100, submission(raw))).isEqualTo(first);
		assertThatThrownBy(() -> service.accept(3, 100, submission(raw))).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.lookup(3, 100, first.receiptId())).isInstanceOf(BusinessException.class);
	}

	@Test
	void rejectsWrongLabelsMissingAckWithdrawnSnapshotAndRetiredReceipt() throws Exception {
		Submission valid = submission(raw);
		Submission invalid = new Submission(UUID.randomUUID(), valid.eventId(), valid.occurredAt(),
			valid.policyVersion(), valid.matchingRuleVersion(), valid.settingsSnapshotId(), valid.peerCredential(),
			valid.evidence());
		assertThatThrownBy(() -> service.accept(1, 100, invalid)).isInstanceOf(BusinessException.class);
		assertThat(jdbc.queryForObject("select count(*) from keyring_peer_encounter", Long.class)).isZero();
		jdbc.update("update keyring_sync_run set state='PENDING' where keyring_id=101");
		assertThatThrownBy(() -> service.accept(1, 100, valid)).isInstanceOf(BusinessException.class);
		jdbc.update("update keyring_sync_run set state='ACKED' where keyring_id=101");
		jdbc.update("update settings_snapshot set redacted_at=? where user_id=2", Timestamp.from(metAt.plusSeconds(1)));
		assertThatThrownBy(() -> service.accept(1, 100, valid)).isInstanceOf(BusinessException.class);
		jdbc.update("update settings_snapshot set redacted_at=null where user_id=2");
		var accepted = service.accept(1, 100, valid);
		jdbc.update("update keyring_peer_encounter set retired=true,raw_payload=null,envelope_hash=null,"
			+ "receipt_json=null,peer_credential=null where receipt_id=?", accepted.receiptId());
		assertThatThrownBy(() -> service.accept(1, 100, valid)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.lookup(1, 100, accepted.receiptId())).isInstanceOf(BusinessException.class);
	}

	@Test
	void quarantinesAuthenticatedReviewAndRejectsAnotherOriginalForTheSameEvent() throws Exception {
		byte[] review = raw.clone();
		review[25] = 3;
		review[27] = 2;
		signSynthetic(review, 1);
		var accepted = service.accept(1, 100, submission(review));
		assertThat(accepted.encounterStatus()).isEqualTo("REVIEW_QUARANTINED");
		assertThat(accepted.proof().receiptStatus()).isEqualTo("STORED");
		assertThatThrownBy(() -> service.accept(1, 100, submission(raw)))
			.isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
		assertThat(jdbc.queryForObject("select count(*) from keyring_peer_encounter", Long.class)).isEqualTo(1);
	}

	@Test
	void reviewWithoutPeerAcceptsEmptyCredentialButConfirmedRecordDoesNot() throws Exception {
		byte[] review = emptyPeerReview(raw);
		Submission request = submission(review);
		assertThat(request.peerCredential()).isEmpty();
		assertThat(service.accept(1, 100, request).encounterStatus()).isEqualTo("REVIEW_QUARANTINED");
		Submission normal = submission(raw);
		Submission missing = new Submission(normal.submissionId(), normal.eventId(), normal.occurredAt(),
			normal.policyVersion(), normal.matchingRuleVersion(), normal.settingsSnapshotId(), "", normal.evidence());
		assertThatThrownBy(() -> service.accept(1, 100, missing)).isInstanceOf(BusinessException.class)
			.extracting("errorCode").isEqualTo(ErrorCode.INVALID_DEVICE_PROOF);
	}

	@Test
	void unsupportedReviewBytesStayOnDeviceWithoutDurableCopyOrDeletionReceipt() throws Exception {
		Submission source = submission(raw);
		for (boolean missingPeer : new boolean[] {true, false}) {
			byte[] review = raw.clone();
			review[25] = 3;
			review[27] = 2;
			int evidence = evidence(review);
			if (missingPeer) {
				review[evidence + 794] = 0;
				Arrays.fill(review, evidence + 437, evidence + 794, (byte)0);
			} else {
				int packet = 24 + 52 + 230;
				review[packet + 14 + 34 + 100] ^= 1;
				CRC32 crc = new CRC32();
				crc.update(review, packet, 244);
				le(review).putInt(packet + 244, (int)crc.getValue());
				System.arraycopy(sha(Arrays.copyOfRange(review, packet, packet + 248)), 0, review, evidence + 48, 32);
			}
			signSynthetic(review, 1);
			Submission request = new Submission(uuid(review, 8), source.eventId(), source.occurredAt(),
				source.policyVersion(), source.matchingRuleVersion(), source.settingsSnapshotId(),
				missingPeer ? "" : source.peerCredential(), new DeviceProof("tingle-encounter-v2", encode(review)));
			assertThatThrownBy(() -> service.accept(1, 100, request))
				.isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.POLICY_UNRESOLVED);
			assertThat(jdbc.queryForObject("select count(*) from keyring_peer_encounter", Long.class)).isZero();
		}
	}

	@Test
	void rejectsMissingOrDifferentFullServerPinAndOversizedInputBeforeAllocation() {
		for (String pin : new String[] {"", "0".repeat(64), "03a107bff3ce10be"}) {
			ReflectionTestUtils.setField(service, "expectedServerPublicKeyHex", pin);
			assertThatThrownBy(() -> service.accept(1, 100, submission(raw))).isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.POLICY_UNRESOLVED);
		}
		Submission valid = submission(raw);
		Submission huge = new Submission(valid.submissionId(), valid.eventId(), valid.occurredAt(),
			valid.policyVersion(), valid.matchingRuleVersion(), valid.settingsSnapshotId(), valid.peerCredential(),
			new DeviceProof("tingle-encounter-v2", "A".repeat(4097)));
		assertThatThrownBy(() -> service.accept(1, 100, huge)).isInstanceOf(BusinessException.class)
			.extracting("errorCode").isEqualTo(ErrorCode.INVALID_DEVICE_PROOF);
		assertThat(jdbc.queryForObject("select count(*) from keyring_peer_encounter", Long.class)).isZero();
	}

	@Test
	void rejectsSignedOwnerRefsThatDisagreeWithHistoryAndSameAccountCounterpart() {
		for (long device : new long[] {100, 101}) {
			UUID original = jdbc.queryForObject("select owner_ref from keyring_peer_ownership where keyring_id=?",
				UUID.class, device);
			jdbc.update("update keyring_peer_ownership set owner_ref=? where keyring_id=?", UUID.randomUUID(), device);
			assertThatThrownBy(() -> service.verifySubmission(1, 100, submission(raw)))
				.isInstanceOf(BusinessException.class).extracting("errorCode")
				.isEqualTo(ErrorCode.INVALID_DEVICE_PROOF);
			jdbc.update("update keyring_peer_ownership set owner_ref=? where keyring_id=?", original, device);
		}
		jdbc.update("update keyring_peer_ownership set owner_user_id=1 where keyring_id=101");
		assertThatThrownBy(() -> service.verifySubmission(1, 100, submission(raw)))
			.isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_DEVICE_PROOF);
		assertThat(jdbc.queryForObject("select count(*) from keyring_peer_encounter", Long.class)).isZero();
	}

	@Test
	void appliesRevocationAtTheEncounterTimeAndPreservesPreviouslyValidEvidence() {
		for (Instant revoked : new Instant[] {metAt.minusSeconds(1), metAt}) {
			jdbc.update("update keyring set credential_revoked_at=? where id=101", Timestamp.from(revoked));
			assertThatThrownBy(() -> service.verifySubmission(1, 100, submission(raw)))
				.isInstanceOf(BusinessException.class).extracting("errorCode")
				.isEqualTo(ErrorCode.INVALID_DEVICE_PROOF);
		}
		jdbc.update("update keyring set credential_revoked_at=? where id=101", Timestamp.from(metAt.plusSeconds(1)));
		assertThat(service.verifySubmission(1, 100, submission(raw)).peer().deviceId()).isEqualTo(101);
		assertThat(service.accept(1, 100, submission(raw)).proof().receiptStatus()).isEqualTo("STORED");
	}

	@Test
	void replacedRegistryKeyWithoutHistoricalRevocationEvidenceIsUnresolved() {
		String other = encode(join(HexFormat.of().parseHex("302a300506032b6570032100"), new byte[32]));
		jdbc.update("update keyring set credential_public_key=? where id=100", other);
		assertThatThrownBy(() -> service.accept(1, 100, submission(raw)))
			.isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.POLICY_UNRESOLVED);
		assertThat(jdbc.queryForObject("select count(*) from keyring_peer_encounter", Long.class)).isZero();
	}

	@Test
	void reviewNeedsExplicitApprovalBeforeStoringOrIssuingDeletionPermission() throws Exception {
		byte[] review = raw.clone();
		review[25] = 3;
		review[27] = 2;
		signSynthetic(review, 1);
		ReflectionTestUtils.setField(service, "reviewIntakeApproved", false);
		assertThatThrownBy(() -> service.accept(1, 100, submission(review)))
			.isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.POLICY_UNRESOLVED);
		assertThat(jdbc.queryForObject("select count(*) from keyring_peer_encounter", Long.class)).isZero();
		assertThat(service.accept(1, 100, submission(raw)).encounterStatus()).isEqualTo("WAITING_COUNTERPART");
	}

	@Test
	void confirmedProofCannotBypassEitherFrozenIncompleteOrDisallowedRun() {
		for (long device : new long[] {100, 101}) {
			byte[] original = frozen(device);
			for (int offset : new int[] {481, 482}) {
				byte[] changed = original.clone();
				changed[offset] = 0;
				storeFrozen(device, changed);
				assertThatThrownBy(() -> service.accept(1, 100, submission(raw)))
					.isInstanceOf(BusinessException.class).extracting("errorCode")
					.isEqualTo(ErrorCode.INVALID_DEVICE_PROOF);
			}
			storeFrozen(device, original);
		}
		assertThat(jdbc.queryForObject("select count(*) from keyring_peer_encounter", Long.class)).isZero();
	}

	@Test
	void appliesBothFrozenRelationshipRestrictionsAtTheOriginalMeetingBoundary() {
		for (long device : new long[] {100, 101}) {
			byte[] original = frozen(device);
			long other = device == 100 ? 101 : 100;
			UUID peer = jdbc.queryForObject("select owner_ref from keyring_peer_ownership where keyring_id=?",
				UUID.class, other);
			for (long expires : new long[] {metAt.getEpochSecond() - 1, metAt.getEpochSecond()}) {
				storeFrozen(device, restriction(original, peer, 1, expires));
				assertThat(service.verifySubmission(1, 100, submission(raw)).kind())
					.isEqualTo(Ter2ProofVerifier.Kind.CONFIRMED);
			}
			storeFrozen(device, restriction(original, peer, 1, metAt.getEpochSecond() + 1));
			assertThatThrownBy(() -> service.verifySubmission(1, 100, submission(raw)))
				.isInstanceOf(BusinessException.class).extracting("errorCode")
				.isEqualTo(ErrorCode.INVALID_DEVICE_PROOF);
			storeFrozen(device, restriction(original, peer, 2, 0));
			assertThatThrownBy(() -> service.verifySubmission(1, 100, submission(raw)))
				.isInstanceOf(BusinessException.class).extracting("errorCode")
				.isEqualTo(ErrorCode.INVALID_DEVICE_PROOF);
			storeFrozen(device, original);
		}
	}

	@Test
	void validlySignedOffProfileCannotProduceAConfirmedReceiptEvenWithAnAckedRun() throws Exception {
		byte[] off = raw.clone();
		int ownProfile = 24 + 52 + 34;
		off[ownProfile + 195] &= (byte)~1;
		byte[] profile = Arrays.copyOfRange(off, ownProfile, ownProfile + 196);
		byte[] digest = sha(profile);
		System.arraycopy(digest, 0, off, 24 + 52 + 16, 16);
		int evidence = evidence(off);
		System.arraycopy(digest, 0, off, evidence + 16, 32);
		int credential = evidence + 80;
		System.arraycopy(digest, 0, off, credential + 133, 32);
		byte[] serverSeed = new byte[32];
		for (int index = 0; index < serverSeed.length; index++) {
			serverSeed[index] = (byte)index;
		}
		byte[] grantMessage = join("TINGLE-PEER-GRANT-V3\n".getBytes(StandardCharsets.US_ASCII),
			Arrays.copyOfRange(off, credential, credential + 197));
		System.arraycopy(signBytes(grantMessage, serverSeed), 0, off, credential + 197, 64);
		byte[] ownSeed = new byte[32];
		Arrays.fill(ownSeed, (byte)1);
		byte[] authMessage = join("TINGLE-PEER-AUTH-V1\n".getBytes(StandardCharsets.US_ASCII),
			join(Arrays.copyOfRange(off, credential + 325, credential + 357),
				Arrays.copyOfRange(off, credential, credential + 261)));
		System.arraycopy(signBytes(authMessage, ownSeed), 0, off, credential + 261, 64);
		signSynthetic(off, 1);
		byte[] payload = frozen(100);
		System.arraycopy(profile, 0, payload, 4, 196);
		System.arraycopy(off, credential, payload, 200, 261);
		storeFrozen(100, payload);
		Submission request = submission(off); // Independent TER2 cryptography is valid; private readiness is not.
		assertThatThrownBy(() -> service.accept(1, 100, request)).isInstanceOf(BusinessException.class)
			.extracting("errorCode").isEqualTo(ErrorCode.INVALID_DEVICE_PROOF);
		assertThat(jdbc.queryForObject("select count(*) from keyring_peer_encounter", Long.class)).isZero();
	}

	private static byte[] restriction(byte[] original, UUID owner, int kind, long expires) {
		byte[] payload = Arrays.copyOf(original, 512);
		le(payload).putShort(477, (short)1);
		byte[] ownerBytes = ByteBuffer.allocate(16).putLong(owner.getMostSignificantBits())
			.putLong(owner.getLeastSignificantBits()).array();
		System.arraycopy(ownerBytes, 0, payload, 487, 16);
		payload[503] = (byte)kind;
		le(payload).putLong(504, expires);
		return payload;
	}

	private static byte[] frozen(long device) {
		return jdbc.queryForObject("select payload from keyring_sync_run where keyring_id=?", byte[].class, device);
	}

	private static void storeFrozen(long device, byte[] payload) {
		jdbc.update("update keyring_sync_run set payload=?,payload_hash=? where keyring_id=?",
			payload, hash(payload), device);
	}

	private void seedHistory(long user, byte[] credential, byte[] payload) {
		long device = le(credential).getLong(9);
		UUID generation = uuid(credential, 29);
		String keyId = "ed25519-" + HexFormat.of().formatHex(credential, 165, 173);
		String publicKey = encode(join(HexFormat.of().parseHex("302a300506032b6570032100"),
			Arrays.copyOfRange(credential, 165, 197)));
		jdbc.update("insert into users values (?,'ACTIVE')", user);
		jdbc.update("insert into keyring(id,owner_user_id,credential_public_key) values (?,?,?)",
			device, user, publicKey);
		jdbc.update("insert into keyring_peer_ownership values (?,?,?,?,?,?,?,?,?,null)", device, generation, 1,
			user, uuid(credential, 93), "XIAO-" + HexFormat.of().formatHex(credential, 1, 9), keyId, publicKey,
			Timestamp.from(metAt.minusSeconds(60)));
		String canonical = "{\"syntheticSourceEvidence\":true}";
		jdbc.update("insert into settings_snapshot values (?,10,'settings-source-evidence-v1',?,?,?,null)",
			user, canonical, snapshots.hash(canonical), Timestamp.from(metAt));
		jdbc.update("insert into keyring_sync_run values "
			+ "(?,?,?,?,?,10,'ACKED',?,'tingle-device-proof-v2',?,?,?,?,?,?,?,?)",
			uuid(credential, 45), device, user, generation, 1, "tingle-keyring-settings-v3", keyId, payload,
			hash(payload), encode(Arrays.copyOfRange(credential, 61, 93)), Timestamp.from(metAt), Timestamp.from(metAt),
			"a".repeat(64), UUID.randomUUID());
	}

	private Submission submission(byte[] payload) {
		int evidence = evidence(payload);
		var decoded = new Ter2ProofVerifier().verify(payload, hash(payload), server,
			Arrays.copyOfRange(payload, evidence + 245, evidence + 277),
			Arrays.copyOfRange(payload, evidence + 602, evidence + 634));
		return new Submission(decoded.submissionId(), decoded.eventId(), decoded.occurredAt(), "tingle-policy-v2",
			"r2-c-20261106", decoded.own().syncRunId(), decoded.peer() == null ? "" : encode(decoded.peer().raw()),
			new DeviceProof("tingle-encounter-v2", encode(payload)));
	}

	private static byte[] counterpart(byte[] original) throws Exception {
		byte[] result = original.clone();
		int evidence = evidence(result);
		byte[] own = Arrays.copyOfRange(original, evidence + 80, evidence + 437);
		byte[] peer = Arrays.copyOfRange(original, evidence + 437, evidence + 794);
		byte[] ownProfile = Arrays.copyOfRange(original, 24 + 52, 24 + 52 + 230);
		byte[] peerProfile = Arrays.copyOfRange(original, 24 + 52 + 230 + 14, 24 + 52 + 230 + 14 + 230);
		System.arraycopy(peerProfile, 0, result, 24 + 52, 230);
		int packet = 24 + 52 + 230;
		le(result).putLong(packet + 6, 1);
		System.arraycopy(ownProfile, 0, result, packet + 14, 230);
		CRC32 crc = new CRC32();
		crc.update(result, packet, 244);
		le(result).putInt(packet + 244, (int)crc.getValue());
		le(result).putLong(24 + 24, 1);
		le(result).putLong(evidence + 8, 1);
		System.arraycopy(sha(Arrays.copyOfRange(peerProfile, 34, 230)), 0, result, evidence + 16, 32);
		System.arraycopy(sha(Arrays.copyOfRange(result, packet, packet + 248)), 0, result, evidence + 48, 32);
		System.arraycopy(peer, 0, result, evidence + 80, 357);
		System.arraycopy(own, 0, result, evidence + 437, 357);
		signSynthetic(result, 2);
		return result;
	}

	private static void signSynthetic(byte[] result, int seedValue) throws Exception {
		byte[] submission = sha(Arrays.copyOfRange(result, 24, result.length - 64));
		System.arraycopy(submission, 0, result, 8, 16);
		result[14] = (byte)((result[14] & 15) | 0x50);
		result[16] = (byte)((result[16] & 63) | 0x80);
		byte[] seed = new byte[32];
		Arrays.fill(seed, (byte)seedValue);
		byte[] message = join("TINGLE-ENCOUNTER-V2\n".getBytes(StandardCharsets.US_ASCII),
			sha(Arrays.copyOf(result, result.length - 64)));
		System.arraycopy(signBytes(message, seed), 0, result, result.length - 64, 64);
	}

	private static byte[] emptyPeerReview(byte[] original) throws Exception {
		int evidence = evidence(original);
		byte[] result = new byte[24 + 282 + 795 + 64];
		System.arraycopy(original, 0, result, 0, 24 + 282);
		System.arraycopy(original, evidence, result, 24 + 282, 795);
		le(result).putShort(4, (short)282).putShort(24 + 50, (short)0);
		result[25] = 3;
		result[27] = 2;
		int moved = 24 + 282;
		System.arraycopy(sha(new byte[0]), 0, result, moved + 48, 32);
		Arrays.fill(result, moved + 437, moved + 795, (byte)0);
		signSynthetic(result, 1);
		return result;
	}

	private static byte[] signBytes(byte[] message, byte[] seed) throws Exception {
		var key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(join(
			HexFormat.of().parseHex("302e020100300506032b657004220420"), seed)));
		var signer = Signature.getInstance("Ed25519");
		signer.initSign(key);
		signer.update(message);
		return signer.sign();
	}

	private static int evidence(byte[] payload) {
		return 24 + Short.toUnsignedInt(le(payload).getShort(4));
	}

	private static ByteBuffer le(byte[] payload) {
		return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static UUID uuid(byte[] payload, int offset) {
		ByteBuffer source = ByteBuffer.wrap(payload, offset, 16);
		return new UUID(source.getLong(), source.getLong());
	}

	private static byte[] join(byte[] first, byte[] second) {
		return ByteBuffer.allocate(first.length + second.length).put(first).put(second).array();
	}

	private static byte[] decode(String value) {
		return Base64.getUrlDecoder().decode(value);
	}

	private static String encode(byte[] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	private static String hash(byte[] value) {
		return HexFormat.of().formatHex(sha(value));
	}

	private static byte[] sha(byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}
}
