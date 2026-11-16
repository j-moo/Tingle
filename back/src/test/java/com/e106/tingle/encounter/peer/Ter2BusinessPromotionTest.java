package com.e106.tingle.encounter.peer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import com.e106.tingle.SharedPostgres;
import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.encounter.EncounterEvaluationGateway;
import com.e106.tingle.encounter.EncounterEvaluationWorker;
import com.e106.tingle.encounter.EncounterReceipts;
import com.e106.tingle.encounter.MatchingEvaluationRuntimeVerifier;
import com.e106.tingle.encounter.StoredEncounterInputLookup;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.DeviceProof;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;
import com.e106.tingle.encounter.proof.Ter2ProofVerifier;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.DevSettingsIssuer;
import com.e106.tingle.keyring.ownership.PeerOwnershipService;
import com.e106.tingle.keyring.proof.ServerSettingsSigner;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.settings.snapshot.SettingsSnapshotJson;

import tools.jackson.databind.json.JsonMapper;

/** Disposable DB and public C vectors. Approval/runtime fixtures are synthetic, never production approval. */
class Ter2BusinessPromotionTest {

	private static final JdbcConnectionDetails POSTGRES = SharedPostgres.newDatabase();
	private static JdbcTemplate jdbc;
	private static DriverManagerDataSource dataSource;
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final SettingsSnapshotJson snapshots = new SettingsSnapshotJson();
	private final AtomicInteger evaluations = new AtomicInteger();
	private Ter2ReceiptService service;
	private Ter2BusinessPromotion promotion;
	private Ter2BusinessReleaseResolver releases;
	private EncounterEvaluationWorker worker;
	private byte[] raw;
	private byte[] server;
	private Instant metAt;
	private UUID releaseId;

	@BeforeAll
	static void schema() {
		dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		new ResourceDatabasePopulator(new ClassPathResource("database/schema.sql"),
			new ClassPathResource("database/keyring-peer-ownership-270.sql"),
			new ClassPathResource("database/keyring-peer-encounter-270.sql")).execute(dataSource);
	}

	@BeforeEach
	void independentCryptographicAndReleaseFixtures() throws Exception {
		jdbc.execute("truncate matching_participant,matching,keyring_sync_run,settings_snapshot,keyring,users,"
			+ "rule_release,rule_artifact,keyring_peer_encounter,keyring_peer_ownership,keyring_peer_owner cascade");
		byte[] bundle;
		try (var input = getClass().getResourceAsStream("/keyring/app-flow-v3-vectors.json")) {
			var vectors = mapper.readTree(input).path("vectors");
			raw = decode(vectors.path("encounter").path("base64url").asString());
			bundle = decode(vectors.path("bundle").path("base64url").asString());
			server = decode(vectors.path("serverPublic").path("base64url").asString());
		}
		metAt = Instant.ofEpochSecond(le(raw).getLong(56));
		Clock clock = Clock.fixed(metAt.plusSeconds(86400), ZoneOffset.UTC);
		var transactions = new DataSourceTransactionManager(dataSource);
		byte[] seed = new byte[32];
		for (int index = 0; index < seed.length; index++) {
			seed[index] = (byte)index;
		}
		var factory = KeyFactory.getInstance("Ed25519");
		var signer = new ServerSettingsSigner(factory.generatePrivate(new PKCS8EncodedKeySpec(join(
			HexFormat.of().parseHex("302e020100300506032b657004220420"), seed))),
			factory.generatePublic(new X509EncodedKeySpec(join(HexFormat.of().parseHex("302a300506032b6570032100"),
				server))));
		// The published host vector reuses a run UUID; real storage requires distinct global run identities.
		raw = distinctPeerRun(raw, signer);
		var beans = new StaticListableBeanFactory();
		beans.addBean("signer", new DevSettingsIssuer(jdbc, signer));
		var owners = new PeerOwnershipService(jdbc);
		service = new Ter2ReceiptService(jdbc, owners, beans.getBeanProvider(DevSettingsIssuer.class), clock,
			mapper, snapshots, transactions);
		ReflectionTestUtils.setField(service, "expectedServerPublicKeyHex", HexFormat.of().formatHex(server));
		int offset = evidence(raw);
		seedHistory(1, Arrays.copyOfRange(raw, offset + 80, offset + 437), bundle);
		byte[] peer = Arrays.copyOfRange(raw, offset + 437, offset + 794);
		byte[] peerBundle = bundle.clone();
		System.arraycopy(raw, 24 + 52 + 230 + 14 + 34, peerBundle, 4, 196);
		System.arraycopy(peer, 0, peerBundle, 200, 261);
		seedHistory(2, peer, peerBundle);
		service.accept(1, 100, submission(raw));
		service.accept(2, 101, submission(counterpart(raw)));
		publishSyntheticRelease();
		releases = new Ter2BusinessReleaseResolver(jdbc, mapper);
		ReflectionTestUtils.setField(releases, "approved", true);
		ReflectionTestUtils.setField(releases, "configuredRelease", releaseId.toString());
		String candidate = jdbc.queryForObject("select approval_hash from rule_release where id=?", String.class,
			releaseId);
		ReflectionTestUtils.setField(releases, "configuredHash", candidate);
		beans.addBean("runtime", mock(MatchingEvaluationRuntimeVerifier.class));
		beans.addBean("gateway", (EncounterEvaluationGateway)(input, hash) -> {
			evaluations.incrementAndGet();
			throw new BusinessException(ErrorCode.POLICY_UNRESOLVED);
		});
		EncounterReceipts receipts = new EncounterReceipts(jdbc, mapper);
		// 최신 develop의 탈퇴 후 재매칭 제한 검사도 동일한 시험 회원 저장소를 사용한다.
		// 실제 탈퇴 정책을 우회하는 공급자를 추가하지 않고 기존 합성 fixture의 접근 조건을 공유한다.
		var members = mock(MemberAccessRepository.class);
		when(members.canReceiveNewProposal(anyLong())).thenReturn(true);
		worker = new EncounterEvaluationWorker(jdbc, mapper, clock, receipts,
			beans.getBeanProvider(EncounterEvaluationGateway.class),
			beans.getBeanProvider(com.e106.tingle.encounter.EncounterProposalPublisher.class),
			beans.getBeanProvider(com.e106.tingle.encounter.EncounterEvaluationCompletion.class),
			new StandardEnvironment(), transactions, members);
		promotion = new Ter2BusinessPromotion(jdbc, service, new Ter2BusinessProofVerifier(service, owners, jdbc),
			releases, new StoredEncounterInputLookup(jdbc, snapshots), receipts, worker, members,
			beans.getBeanProvider(MatchingEvaluationRuntimeVerifier.class),
			beans.getBeanProvider(EncounterEvaluationGateway.class), transactions, clock, mapper);
		evaluations.set(0);
	}

	@Test
	void promotesBothOriginalsWithSignedDeadlineAndDistinctSnapshotHash() {
		promotion.promote(1, 100, submission(raw));
		long matching = matching();
		assertThat(work(matching).state()).isEqualTo(EncounterEvaluationWorker.State.READY);
		assertThat(jdbc.queryForObject("select opportunity_expires_at from matching where id=?",
			Timestamp.class, matching).toInstant()).isEqualTo(Instant.ofEpochSecond(le(raw).getLong(64)));
		assertThat(work(matching).input().left().inputHash()).isEqualTo(snapshots.hash("{\"synthetic\":true}"));
		promotion.promote(1, 100, submission(raw));
		assertThat(jdbc.queryForObject("select count(*) from matching", Long.class)).isEqualTo(1);
	}

	@Test
	void publishedSurveyOrChangedArtifactCannotStandInForApprovedMatchingRelease() {
		jdbc.update("update rule_artifact set document='{}'::jsonb where kind='MATCHING'");
		assertThatThrownBy(() -> promotion.promote(1, 100, submission(raw))).isInstanceOf(BusinessException.class)
			.extracting("errorCode").isEqualTo(ErrorCode.POLICY_UNRESOLVED);
		assertThat(jdbc.queryForObject("select count(*) from matching", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from keyring_peer_encounter", Long.class)).isEqualTo(2);
	}

	@Test
	void transferredDeviceUsesHistoricalScopeAndWorkerCanClaimWithoutReplacingOwner() {
		jdbc.update("update keyring_peer_ownership set valid_until=? where keyring_id=100",
			Timestamp.from(metAt.plusSeconds(1)));
		jdbc.update("update keyring set owner_user_id=2,owner_generation=? where id=100", UUID.randomUUID());
		promotion.promote(1, 100, submission(raw));
		assertThat(worker.run(matching())).isEqualTo(EncounterEvaluationWorker.State.BLOCKED);
		assertThat(evaluations).hasValue(1);
		assertThat(jdbc.queryForObject("select owner_user_id from keyring where id=100", Long.class)).isEqualTo(2);
	}

	@Test
	void redactionAndRetiredOriginalPreventWorkerClaimEvenAfterPromotion() {
		promotion.promote(1, 100, submission(raw));
		jdbc.update("update settings_snapshot set redacted_at=? where user_id=1", Timestamp.from(metAt));
		assertThat(worker.run(matching())).isEqualTo(EncounterEvaluationWorker.State.STALE);
		assertThat(evaluations).hasValue(0);
	}

	@Test
	void credentialRevokedAtTheMeetingCannotProceedThroughHistoricalWorkerPath() {
		promotion.promote(1, 100, submission(raw));
		jdbc.update("update keyring set credential_revoked_at=? where id=100", Timestamp.from(metAt));
		assertThat(worker.run(matching())).isEqualTo(EncounterEvaluationWorker.State.STALE);
		assertThat(evaluations).hasValue(0);
	}

	@Test
	void credentialRevokedBeforeTheMeetingCannotProceedThroughHistoricalWorkerPath() {
		promotion.promote(1, 100, submission(raw));
		jdbc.update("update keyring set credential_revoked_at=? where id=100",
			Timestamp.from(metAt.minusSeconds(1)));
		assertThat(worker.run(matching())).isEqualTo(EncounterEvaluationWorker.State.STALE);
		assertThat(evaluations).hasValue(0);
	}

	@Test
	void credentialRevokedAfterTheMeetingPreservesTheValidHistoricalWorkerPath() {
		promotion.promote(1, 100, submission(raw));
		jdbc.update("update keyring set credential_revoked_at=? where id=100",
			Timestamp.from(metAt.plusSeconds(1)));
		assertThat(worker.run(matching())).isEqualTo(EncounterEvaluationWorker.State.BLOCKED);
		assertThat(evaluations).hasValue(1);
	}

	@Test
	void keyringVerdictQualifiesBothConfirmedOriginalsAndRejectsRetiredOnes() {
		promotion.promote(1, 100, submission(raw));
		long matching = matching();
		EncounterEvaluationWorker.Work work = work(matching);
		KeyringVerdictEvaluator evaluator = verdict();
		var result = evaluator.evaluate(work.input(), work.inputHash());
		assertThat(result.outcome()).isEqualTo(EncounterEvaluationGateway.Outcome.QUALIFIED);
		assertThat(result.evaluation()).containsEntry("source", "KEYRING_VERDICT")
			.containsEntry("matchingVersion", "r2-c-20261106")
			.containsEntry("productPolicyVersion", "tingle-policy-v2");
		assertThat(result.historicalEvidence().manifest()).containsEntry("releaseId", releaseId.toString())
			.containsEntry("surveyVersion", "NOT_USED");

		// 저장된 판정·근거는 다시 만든 키링 판정과 같을 때만 믿는다.
		jdbc.update("update matching set evaluation=cast(? as jsonb) where id=?",
			mapper.writeValueAsString(result.evaluation()), matching);
		MatchProposal stored = mock(MatchProposal.class);
		when(stored.getId()).thenReturn(matching);
		when(stored.getUserLow()).thenReturn(1L);
		when(stored.getEvaluation()).thenReturn(new java.util.HashMap<>(result.evaluation()));
		jdbc.update("update matching_participant set request_state=jsonb_set(request_state,'{"
			+ EncounterEvaluationWorker.WORKFLOW_KEY + "}',to_jsonb(cast(? as text))) "
			+ "where matching_id=? and user_id=1",
			mapper.writeValueAsString(new EncounterEvaluationWorker.Work(work.generationId(), UUID.randomUUID(),
				work.inputHash(), EncounterEvaluationWorker.State.SUCCEEDED, 1, null, null, work.input())), matching);
		assertThat(evaluator.verify(stored, result.historicalEvidence())).isTrue();
		// 설정이 다음 조합으로 바뀌어도 이미 고정한 당시 근거는 그대로 유효하다.
		ReflectionTestUtils.setField(releases, "configuredRelease", UUID.randomUUID().toString());
		assertThat(evaluator.verify(stored, result.historicalEvidence())).isTrue();
		when(stored.getEvaluation()).thenReturn(java.util.Map.of("source", "KEYRING_VERDICT", "eligible", false));
		assertThat(evaluator.verify(stored, result.historicalEvidence())).isFalse();

		// 한쪽 원본이 철회되면 키링 판정도 더는 성립하지 않는다.
		jdbc.update("update keyring_peer_encounter set retired=true where owner_user_id=2");
		assertThatThrownBy(() -> evaluator.evaluate(work.input(), work.inputHash()))
			.isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.POLICY_UNRESOLVED);
	}

	@Test
	void keyringVerdictBuildsMatchReasonsFromEachPersonsSnapshotSurvey() {
		// 만남 당시 설정 기록(input_json.analysis.surveyId)이 가리키는 본인 설문에서 근거를 읽는다.
		for (long user : List.of(1L, 2L)) {
			long survey = jdbc.queryForObject("insert into survey(user_id,survey_version,completed_at) "
				+ "values (?,'SIS-Q-1.1-100',?) returning id", Long.class, user, Timestamp.from(metAt));
			jdbc.update("insert into survey_answer(survey_id,question_id,answer_value,answer_type) values "
				+ "(?,'Q034','3','NUMBER'),(?,'Q069','3','NUMBER'),(?,'Q053','nearby','STRING')",
				survey, survey, survey);
			String canonical = "{\"analysis\":{\"surveyId\":" + survey + ",\"surveyVersion\":\"SIS-Q-1.1-100\"}}";
			jdbc.update("update settings_snapshot set input_json=?,input_hash=? "
				+ "where user_id=? and settings_version=10", canonical, snapshots.hash(canonical), user);
		}
		promotion.promote(1, 100, submission(raw));
		EncounterEvaluationWorker.Work work = work(matching());
		var evidence = verdict().evaluate(work.input(), work.inputHash()).historicalEvidence();
		assertThat(evidence.sourceEvidence()).extracting(item -> item.fact())
			.containsExactly("두 분 다 하루에 몇 번씩 연락하는 게 좋대요.");
	}

	@Test
	void keyringVerdictRequiresTheConfiguredApprovedRelease() {
		promotion.promote(1, 100, submission(raw));
		EncounterEvaluationWorker.Work work = work(matching());
		// 접수(승격) 이후 설정이 다른 조합으로 바뀌어도 처리 중인 만남은 고정된 조합으로 평가된다.
		ReflectionTestUtils.setField(releases, "configuredHash", "0".repeat(64));
		assertThatThrownBy(() -> verdict().requireApprovedRelease(work.input(), null, null))
			.isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.POLICY_UNRESOLVED);
		assertThat(verdict().evaluate(work.input(), work.inputHash()).outcome())
			.isEqualTo(EncounterEvaluationGateway.Outcome.QUALIFIED);
		// 고정된 조합이 게시 상태가 아니면 평가하지 않는다.
		jdbc.update("update rule_release set published_at=null where id=?", releaseId);
		assertThatThrownBy(() -> verdict().evaluate(work.input(), work.inputHash()))
			.isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.POLICY_UNRESOLVED);
		assertThatThrownBy(() -> verdict().evaluate(work.input(), "f".repeat(64)))
			.isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
	}

	@Test
	void transientDatabaseErrorIsRetriedNotTreatedAsMissingPolicy() {
		JdbcTemplate broken = mock(JdbcTemplate.class);
		when(broken.query(org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
			org.mockito.ArgumentMatchers.<Object>any()))
			.thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));
		var resolver = new Ter2BusinessReleaseResolver(broken, mapper);
		ReflectionTestUtils.setField(resolver, "approved", true);
		ReflectionTestUtils.setField(resolver, "configuredRelease", releaseId.toString());
		ReflectionTestUtils.setField(resolver, "configuredHash", "a".repeat(64));
		assertThatThrownBy(() -> resolver.resolve("tingle-policy-v2", "r2-c-20261106", metAt))
			.isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
	}

	private KeyringVerdictEvaluator verdict() {
		return new KeyringVerdictEvaluator(jdbc, mapper, new EncounterReceipts(jdbc, mapper),
			new StoredEncounterInputLookup(jdbc, snapshots), releases);
	}

	@Test
	void retiredOriginalCannotBePromotedOrEvaluated() {
		promotion.promote(1, 100, submission(raw));
		jdbc.update("update keyring_peer_encounter set retired=true where owner_user_id=1");
		assertThat(worker.run(matching())).isEqualTo(EncounterEvaluationWorker.State.STALE);
		assertThat(evaluations).hasValue(0);
	}

	private long matching() {
		return jdbc.queryForObject("select id from matching", Long.class);
	}

	private EncounterEvaluationWorker.Work work(long matching) {
		String encoded = jdbc.queryForObject("select request_state->>? from matching_participant "
			+ "where matching_id=? and user_id=1", String.class, EncounterEvaluationWorker.WORKFLOW_KEY, matching);
		return mapper.readValue(encoded, EncounterEvaluationWorker.Work.class);
	}

	private void publishSyntheticRelease() {
		UUID matching = UUID.randomUUID();
		UUID policy = UUID.randomUUID();
		String document = "{\"syntheticApprovedRuntimeOnly\":true}";
		String matchingHash = hash(("MATCHING\nr2-c-20261106\nsynthetic\n" + document)
			.getBytes(StandardCharsets.UTF_8));
		String policyHash = hash(("PRODUCT_POLICY\ntingle-policy-v2\nsynthetic\n" + document)
			.getBytes(StandardCharsets.UTF_8));
		for (UUID id : new UUID[] {matching, policy}) {
			boolean isMatching = id.equals(matching);
			jdbc.update("insert into rule_artifact(id,kind,version,schema_version,revision,document,content_hash,"
				+ "created_by,created_at,published_at) values (?,?,?,'synthetic',0,cast(? as jsonb),?,1,?,?)", id,
				isMatching ? "MATCHING" : "PRODUCT_POLICY", isMatching ? "r2-c-20261106" : "tingle-policy-v2",
				document, isMatching ? matchingHash : policyHash, Timestamp.from(metAt), Timestamp.from(metAt));
		}
		String candidate = hash(("synthetic-runtime\nMATCHING:" + matching + ":0:" + matchingHash
			+ "\nPRODUCT_POLICY:" + policy + ":0:" + policyHash).getBytes(StandardCharsets.UTF_8));
		releaseId = UUID.randomUUID();
		jdbc.update("insert into rule_release(id,name,engine_version,artifact_ids,created_by,created_at,"
			+ "validation_id,validation_hash,validation_passed,approval_id,approval_hash,approved_by,approved_at,"
			+ "published_by,published_at) values (?,'synthetic','synthetic-runtime',cast(? as jsonb),"
			+ "1,?,?,?,true,?,?,2,?,2,?)", releaseId, mapper.writeValueAsString(new UUID[] {matching, policy}),
			Timestamp.from(metAt), UUID.randomUUID(),
			candidate, UUID.randomUUID(), candidate, Timestamp.from(metAt), Timestamp.from(metAt));
	}

	private void seedHistory(long user, byte[] credential, byte[] payload) {
		long device = le(credential).getLong(9);
		UUID generation = uuid(credential, 29);
		String keyId = "ed25519-" + HexFormat.of().formatHex(credential, 165, 173);
		String publicKey = encode(join(HexFormat.of().parseHex("302a300506032b6570032100"),
			Arrays.copyOfRange(credential, 165, 197)));
		jdbc.update("insert into users(id,provider,provider_user_id,status,created_at,signup_completed_at) "
			+ "overriding system value values (?,'GOOGLE',?,'ACTIVE',?,?)", user, "synthetic-" + user,
			Timestamp.from(metAt),
			Timestamp.from(metAt));
		jdbc.update("insert into keyring(id,serial_number,firmware_version,owner_user_id,state,owner_generation,"
			+ "ownership_epoch,credential_key_id,credential_public_key) "
			+ "overriding system value values (?,?,'synthetic',?,'REGISTERED',?,1,?,?)",
			device, "XIAO-" + HexFormat.of().formatHex(credential, 1, 9), user, generation, keyId, publicKey);
		jdbc.update("insert into keyring_peer_ownership values (?,?,?,?,?,?,?,?,?,null)", device, generation, 1,
			user, uuid(credential, 93), "XIAO-" + HexFormat.of().formatHex(credential, 1, 9), keyId, publicKey,
			Timestamp.from(metAt.minusSeconds(60)));
		String canonical = "{\"synthetic\":true}";
		jdbc.update("insert into settings_snapshot(user_id,settings_version,input_format,input_json,input_hash,"
			+ "captured_at) values (?,10,'settings-source-evidence-v1',?,?,?)", user, canonical,
			snapshots.hash(canonical), Timestamp.from(metAt));
		jdbc.update("insert into keyring_sync_run(sync_run_id,keyring_id,owner_user_id,owner_generation,"
			+ "ownership_epoch,"
			+ "settings_version,state,payload_format,protocol_version,credential_key_id,payload,payload_hash,nonce,"
			+ "created_at,acked_at,ack_proof_hash,ack_request_key,request_key,revision) "
			+ "values (?,?,?,?,1,10,'ACKED','tingle-keyring-settings-v3','tingle-device-proof-v2',"
			+ "?,?,?,?,?,?,?, ?,?,0)",
			uuid(credential, 45), device, user, generation, keyId, payload, hash(payload),
			encode(Arrays.copyOfRange(credential, 61, 93)), Timestamp.from(metAt), Timestamp.from(metAt),
			"a".repeat(64), UUID.randomUUID(), UUID.randomUUID());
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

	private static byte[] distinctPeerRun(byte[] original, ServerSettingsSigner serverSigner) throws Exception {
		byte[] result = original.clone();
		int peer = evidence(result) + 437;
		UUID run = UUID.fromString("99999999-9999-4999-8999-999999999999");
		ByteBuffer.wrap(result, peer + 45, 16).putLong(run.getMostSignificantBits())
			.putLong(run.getLeastSignificantBits());
		byte[] grant = join("TINGLE-PEER-GRANT-V3\n".getBytes(StandardCharsets.US_ASCII),
			Arrays.copyOfRange(result, peer, peer + 197));
		System.arraycopy(decode(serverSigner.sign(grant)), 0, result, peer + 197, 64);
		byte[] seed = new byte[32];
		Arrays.fill(seed, (byte)2);
		var key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(join(
			HexFormat.of().parseHex("302e020100300506032b657004220420"), seed)));
		var signer = Signature.getInstance("Ed25519");
		signer.initSign(key);
		signer.update("TINGLE-PEER-AUTH-V1\n".getBytes(StandardCharsets.US_ASCII));
		signer.update(Arrays.copyOfRange(result, peer + 325, peer + 357));
		signer.update(Arrays.copyOfRange(result, peer, peer + 261));
		System.arraycopy(signer.sign(), 0, result, peer + 261, 64);
		signSynthetic(result, 1);
		return result;
	}

	private static void signSynthetic(byte[] result, int seedValue) throws Exception {
		byte[] submission = sha(Arrays.copyOfRange(result, 24, result.length - 64));
		System.arraycopy(submission, 0, result, 8, 16);
		result[14] = (byte)((result[14] & 15) | 0x50);
		result[16] = (byte)((result[16] & 63) | 0x80);
		byte[] seed = new byte[32];
		Arrays.fill(seed, (byte)seedValue);
		var key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(join(
			HexFormat.of().parseHex("302e020100300506032b657004220420"), seed)));
		var signer = Signature.getInstance("Ed25519");
		signer.initSign(key);
		signer.update("TINGLE-ENCOUNTER-V2\n".getBytes(StandardCharsets.US_ASCII));
		signer.update(sha(Arrays.copyOf(result, result.length - 64)));
		System.arraycopy(signer.sign(), 0, result, result.length - 64, 64);
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
