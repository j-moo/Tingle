package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.SharedPostgres;
import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.encounter.EncounterEvaluationGateway.ParticipantInput;
import com.e106.tingle.encounter.EncounterSubmissionProofVerifier.Claims;
import com.e106.tingle.encounter.VerifiedEncounterSubmissionMapper.Prepared;
import com.e106.tingle.encounter.dto.EncounterSyncRequest;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.DeviceProof;
import com.e106.tingle.encounter.dto.EncounterSyncRequest.Submission;
import com.e106.tingle.encounter.dto.EncounterSyncResponse.Receipt;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence.Snapshot;
import com.e106.tingle.withdrawal.WithdrawalIdentityService;

import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL 원자성·잠금·실제 worker 접수 검증. 공식 proof와 저장 ACK 공급 결과만 합성 대체한다. */
@SpringBootTest(classes = VerifiedEncounterIntakePersistenceTest.Config.class, properties = {
	"spring.main.web-application-type=none", "spring.profiles.active=test",
	"spring.jpa.hibernate.ddl-auto=none", "spring.docker.compose.enabled=false",
	"spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:database/schema.sql"
})
class VerifiedEncounterIntakePersistenceTest {
	private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");
	private static final String HASH = "a".repeat(64);
	private static final AtomicReference<EncounterEvaluationGateway> GATEWAY = new AtomicReference<>();

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@Import({EncounterReceipts.class, EncounterEvaluationWorker.class, ReligionEvidenceRetirement.class,
		MemberAccessRepository.class})
	static class Config {
		@Bean
		JdbcConnectionDetails postgresConnection() {
			return SharedPostgres.newDatabase();
		}

		@Bean
		MutableClock clock() {
			return new MutableClock();
		}

		// 새 제안 판단의 탈퇴 관계 제한. 이 시험은 탈퇴 기록이 없다.
		@Bean
		WithdrawalIdentityService withdrawalIdentities() {
			return org.mockito.Mockito.mock(WithdrawalIdentityService.class);
		}

		@Bean
		EncounterEvaluationGateway gateway() {
			return (input, hash) -> GATEWAY.get().evaluate(input, hash);
		}
	}

	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JsonMapper json;
	@Autowired
	private EncounterReceipts receipts;
	@Autowired
	private EncounterEvaluationWorker worker;
	@Autowired
	private ReligionEvidenceRetirement retirement;
	@Autowired
	private PlatformTransactionManager transactions;
	@Autowired
	private MutableClock clock;
	private VerifiedEncounterSubmissionMapper submissions;
	private StoredEncounterInputLookup inputs;
	private MemberAccessRepository members;
	private VerifiedEncounterIntake intake;
	private long low;
	private long high;
	private long firstDevice;
	private long secondDevice;
	private final UUID firstGeneration = UUID.randomUUID();
	private final UUID secondGeneration = UUID.randomUUID();
	private Submission first;
	private Submission second;

	@BeforeEach
	void prepareDisposablePair() throws Exception {
		clock.set(NOW);
		GATEWAY.set(null);
		low = user();
		high = user();
		firstDevice = device(low, firstGeneration);
		secondDevice = device(high, secondGeneration);
		UUID event = UUID.randomUUID();
		first = submission(event, (byte)1);
		second = submission(event, (byte)2);
		submissions = mock(VerifiedEncounterSubmissionMapper.class);
		inputs = mock(StoredEncounterInputLookup.class);
		members = mock(MemberAccessRepository.class);
		when(members.canReceiveNewProposal(anyLong())).thenReturn(true);
		Prepared firstInput = prepared(low, firstDevice, high, secondDevice, firstGeneration, first);
		Prepared secondInput = prepared(high, secondDevice, low, firstDevice, secondGeneration, second);
		when(submissions.prepare(low, firstDevice, first)).thenReturn(firstInput);
		when(submissions.prepare(high, secondDevice, second)).thenReturn(secondInput);
		when(inputs.find(any(), any())).thenAnswer(call -> {
			ParticipantInput participant = call.getArgument(0);
			return Optional.of(new Snapshot(participant.userId(), participant.settingsSnapshotId(), HASH, 1L,
				Snapshot.sourceRef(participant.userId(), 1)));
		});
		intake = intake(worker);
	}

	@Test
	void configuredSyncConnectsBothPublicSubmissionsToTheAtomicStoreAndWorker() {
		var sync = sync(intake);
		var saved = sync.sync(low, new EncounterSyncRequest(firstDevice, List.of(first))).results().getFirst();
		assertThat(saved.receiptStatus()).isEqualTo("STORED");
		assertThat(sync.sync(high, new EncounterSyncRequest(secondDevice, List.of(second)))
			.results().getFirst().receiptStatus()).isEqualTo("STORED");
		assertThat(json.readValue(work(), EncounterEvaluationWorker.Work.class).state())
			.isEqualTo(EncounterEvaluationWorker.State.READY);
		assertThat(sync.sync(low, new EncounterSyncRequest(firstDevice, List.of(first)))
			.results()).containsExactly(saved);
	}

	@Test
	void savedReceiptRecoversEvenIfTheOfficialProofSupplierLaterBecomesUnavailable() {
		var service = sync(intake);
		var request = new EncounterSyncRequest(firstDevice, List.of(first));
		var saved = service.sync(low, request);
		when(submissions.prepare(low, firstDevice, first))
			.thenThrow(new BusinessException(ErrorCode.POLICY_UNRESOLVED));
		assertThat(service.sync(low, request)).isEqualTo(saved);
		assertThat(receiptsCount()).isEqualTo(1);
	}

	@Test
	void configuredSyncPreservesTheExistingDeviceAccessErrorBeforeProofVerification() {
		jdbc.update("update keyring set owner_user_id=? where id=?", high, firstDevice);
		assertThatThrownBy(() -> sync(intake).sync(low, new EncounterSyncRequest(firstDevice, List.of(first))))
			.isInstanceOfSatisfying(BusinessException.class, failure ->
				assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
		assertThat(pairCount()).isZero();
	}

	@Test
	void configuredButUnresolvedPolicyKeepsTheExistingPerItemNotStoredContract() {
		var unsupported = new VerifiedEncounterIntake(jdbc, json, Clock.fixed(NOW, ZoneOffset.UTC), submissions,
			inputs, receipts, worker, members, (policy, rule) -> null, transactions);
		assertThat(sync(unsupported).sync(low, new EncounterSyncRequest(firstDevice, List.of(first)))
			.results()).containsExactly(Receipt.unresolved(first));
		assertThat(pairCount()).isZero();
	}

	@SuppressWarnings("unchecked")
	private EncounterSyncService sync(VerifiedEncounterIntake configured) {
		ObjectProvider<VerifiedEncounterIntake> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(configured);
		return new EncounterSyncService(new EncounterAccessRepository(jdbc), receipts, json, provider, transactions);
	}

	@Test
	void firstReceiptCommitsAndSecondAtomicallyRegistersOneDurableEvaluation() {
		var saved = intake.accept(low, firstDevice, first);
		assertThat(saved.canDeleteLocalRecord()).isTrue();
		assertThat(saved.counterpartDeadlineAt()).isEqualTo(NOW.plusSeconds(7 * 86400));
		assertThat(work()).isNull();
		assertThat(intake.accept(low, firstDevice, first)).isEqualTo(saved);
		intake.accept(high, secondDevice, second);
		var work = json.readValue(work(), EncounterEvaluationWorker.Work.class);
		assertThat(work.state()).isEqualTo(EncounterEvaluationWorker.State.READY);
		assertThat(work.input().left().eventId()).isEqualTo(work.input().right().eventId());
		assertThat(work.input().counterpartDeadlineAt()).isEqualTo(saved.counterpartDeadlineAt());
		assertThat(receiptsCount()).isEqualTo(2);
		assertThat(intake.accept(low, firstDevice, first)).isEqualTo(saved);
	}

	@Test
	void enqueueFailureRollsBackSecondProofAndReceiptWithoutChangingFirstReceipt() {
		var firstReceipt = intake.accept(low, firstDevice, first);
		EncounterEvaluationWorker broken = mock(EncounterEvaluationWorker.class);
		doThrow(new IllegalStateException("synthetic enqueue failure")).when(broken).enqueue(any());
		assertThatThrownBy(() -> intake(broken).accept(high, secondDevice, second))
			.isInstanceOf(IllegalStateException.class);
		assertThat(receiptsCount()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select encounter_signature is null from matching_participant "
			+ "where matching_id=? and user_id=?", Boolean.class, matching(), high)).isTrue();
		assertThat(intake.accept(low, firstDevice, first)).isEqualTo(firstReceipt);
		assertThat(work()).isNull();
	}

	@Test
	void retiredGenerationRejectsReplayAndThePreviouslyUnsubmittedCounterpart() {
		intake.accept(low, firstDevice, first);
		jdbc.update("insert into settings_snapshot(user_id,settings_version,input_format,input_json,input_hash,"
			+ "captured_at,redacted_at,redaction_reason) values (?,1,'test','{}',?,?,?,'PROFILE_RELIGION_WITHDRAWN')",
			low, "0".repeat(64), Timestamp.from(NOW), Timestamp.from(NOW));
		retirement.retire(low);

		assertThatThrownBy(() -> sync(intake).sync(low, new EncounterSyncRequest(firstDevice, List.of(first))))
			.isInstanceOfSatisfying(BusinessException.class, failure ->
				assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
		// 상대방은 아직 영수증이 없고 lookup도 합성 성공하지만, 폐기한 동일 세대는 되살릴 수 없다.
		assertThat(sync(intake).sync(high, new EncounterSyncRequest(secondDevice, List.of(second)))
			.results()).containsExactly(Receipt.unresolved(second));
		assertThat(work()).isNull();
		assertThat(jdbc.queryForObject("select encounter_signature is null from matching_participant "
			+ "where matching_id=? and user_id=?", Boolean.class, matching(), high)).isTrue();
	}

	@Test
	void currentOwnerGenerationAndConsentAreRecheckedBeforeWriting() {
		jdbc.update("update keyring set owner_generation=? where id=?", UUID.randomUUID(), firstDevice);
		assertThatThrownBy(() -> intake.accept(low, firstDevice, first)).isInstanceOf(BusinessException.class);
		assertThat(pairCount()).isZero();
		jdbc.update("update keyring set owner_generation=? where id=?", firstGeneration, firstDevice);
		when(members.canReceiveNewProposal(high)).thenReturn(false);
		assertThatThrownBy(() -> intake.accept(low, firstDevice, first)).isInstanceOf(BusinessException.class);
		assertThat(pairCount()).isZero();
	}

	@Test
	void oppositeSideConcurrentSubmissionsUseOneMatchingAndOneGeneration() throws Exception {
		try (var executor = Executors.newFixedThreadPool(2)) {
			var firstResult = executor.submit(() -> intake.accept(low, firstDevice, first));
			var secondResult = executor.submit(() -> intake.accept(high, secondDevice, second));
			assertThat(firstResult.get(15, TimeUnit.SECONDS).canDeleteLocalRecord()).isTrue();
			assertThat(secondResult.get(15, TimeUnit.SECONDS).canDeleteLocalRecord()).isTrue();
		}
		assertThat(pairCount()).isEqualTo(1);
		assertThat(receiptsCount()).isEqualTo(2);
		assertThat(json.readValue(work(), EncounterEvaluationWorker.Work.class).attempts()).isZero();
	}

	@Test
	void exactDeadlineCannotStoreTheCounterpartOrExtendTheFirstReceipt() {
		var saved = intake.accept(low, firstDevice, first);
		var expired = new VerifiedEncounterIntake(jdbc, json,
			Clock.fixed(NOW.plusSeconds(7 * 86400), ZoneOffset.UTC), submissions, inputs, receipts, worker, members,
			(policy, rule) -> new UUID(0, 77), transactions);
		assertThatThrownBy(() -> expired.accept(high, secondDevice, second)).isInstanceOf(BusinessException.class);
		assertThat(receiptsCount()).isEqualTo(1);
		assertThat(intake.accept(low, firstDevice, first)).isEqualTo(saved);
		assertThat(work()).isNull();
	}

	@Test
	void anotherEventCannotOverwriteTheFirstVerifiedEncounter() throws Exception {
		var saved = intake.accept(low, firstDevice, first);
		Submission changed = submission(UUID.randomUUID(), (byte)2);
		when(submissions.prepare(high, secondDevice, changed)).thenReturn(
			prepared(high, secondDevice, low, firstDevice, secondGeneration, changed));
		assertThatThrownBy(() -> intake.accept(high, secondDevice, changed)).isInstanceOf(BusinessException.class);
		assertThat(receiptsCount()).isEqualTo(1);
		assertThat(intake.accept(low, firstDevice, first)).isEqualTo(saved);
	}

	@Test
	void unsupportedReleaseCannotCreateAMatchingOrDeletePermission() {
		var unsupported = new VerifiedEncounterIntake(jdbc, json, Clock.fixed(NOW, ZoneOffset.UTC), submissions,
			inputs, receipts, worker, members, (policy, rule) -> null, transactions);
		assertThatThrownBy(() -> unsupported.accept(low, firstDevice, first)).isInstanceOf(BusinessException.class);
		assertThat(pairCount()).isZero();
	}

	@Test
	void ambientTransactionCannotHideADeleteReceiptBeforeTheRealCommit() {
		assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
			intake.accept(low, firstDevice, first))).isInstanceOf(IllegalStateException.class);
		assertThat(pairCount()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"WAITING", "NOT_MATCHED"})
	void endedUnproposedPairReusesTheRowWithoutLosingOldReceiptsOrEvaluation(String previousStatus) throws Exception {
		var original = intake.accept(low, firstDevice, first);
		var previous = json.readValue(state("encounter:intake"), VerifiedEncounterIntake.Context.class);
		String oldEvaluationKey = "encounter:evaluation:" + previous.generation();
		String captured = json.writeValueAsString(Map.of("evaluation", Map.of("syntheticResult", "not-matched")));
		if ("NOT_MATCHED".equals(previousStatus)) {
			intake.accept(high, secondDevice, second);
			jdbc.update("update matching set status='NOT_MATCHED',evaluation=cast(? as jsonb) where id=?",
				"{\"syntheticResult\":\"not-matched\"}", matching());
			jdbc.update("update matching_participant set request_state=request_state "
				+ "|| jsonb_build_object(cast(? as text),cast(? as text)) where matching_id=? and user_id=?",
				oldEvaluationKey, captured, matching(), low);
		}
		long originalId = matching();
		clock.set(NOW.plusSeconds(8 * 86400));
		UUID nextEvent = UUID.randomUUID();
		Submission nextFirst = nextSubmission(nextEvent, (byte)1);
		Submission nextSecond = nextSubmission(nextEvent, (byte)2);
		register(nextFirst, nextSecond);
		intake.accept(low, firstDevice, nextFirst);
		assertThat(work()).isNull();
		assertThat(intake.accept(low, firstDevice, first)).isEqualTo(original);
		intake.accept(high, secondDevice, nextSecond);
		assertThat(matching()).isEqualTo(originalId);
		var next = json.readValue(work(), EncounterEvaluationWorker.Work.class);
		assertThat(next.state()).isEqualTo(EncounterEvaluationWorker.State.READY);
		assertThat(next.generationId()).isNotEqualTo(previous.generation());
		assertThat(next.input().metAt()).isEqualTo(clock.instant());
		assertThat(state("encounter:archive:" + previous.generation())).contains(previous.event().toString());
		assertThat(jdbc.queryForObject("select evaluation is null from matching where id=?",
			Boolean.class, matching())).isTrue();
		if ("NOT_MATCHED".equals(previousStatus)) {
			assertThat(state(oldEvaluationKey)).isEqualTo(captured);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"timestamp", "marker"})
	void previousPublicationCannotBeReopenedEvenIfItsStatusWasChangedToNotMatched(String proof) throws Exception {
		var saved = intake.accept(low, firstDevice, first);
		jdbc.update("update matching set status='NOT_MATCHED',proposed_at=? where id=?",
			"timestamp".equals(proof) ? Timestamp.from(NOW) : null, matching());
		if ("marker".equals(proof)) {
			jdbc.update("update matching_participant set request_state=request_state "
				+ "|| jsonb_build_object('encounter:proposal:synthetic','{}') where matching_id=? and user_id=?",
				matching(), low);
		}
		clock.set(NOW.plusSeconds(8 * 86400));
		Submission next = nextSubmission(UUID.randomUUID(), (byte)1);
		when(submissions.prepare(low, firstDevice, next))
			.thenReturn(prepared(low, firstDevice, high, secondDevice, firstGeneration, next));
		assertThatThrownBy(() -> intake.accept(low, firstDevice, next)).isInstanceOf(BusinessException.class);
		assertThat(intake.accept(low, firstDevice, first)).isEqualTo(saved);
	}

	@Test
	void evaluationWithoutItsImmutableGenerationReceiptCannotBeDiscardedForReuse() throws Exception {
		var saved = intake.accept(low, firstDevice, first);
		String originalContext = state("encounter:intake");
		jdbc.update("update matching set status='NOT_MATCHED',evaluation=cast(? as jsonb) where id=?",
			"{\"syntheticUnarchived\":true}", matching());
		clock.set(NOW.plusSeconds(8 * 86400));
		Submission next = nextSubmission(UUID.randomUUID(), (byte)1);
		when(submissions.prepare(low, firstDevice, next))
			.thenReturn(prepared(low, firstDevice, high, secondDevice, firstGeneration, next));
		assertThatThrownBy(() -> intake.accept(low, firstDevice, next)).isInstanceOf(BusinessException.class);
		assertThat(state("encounter:intake")).isEqualTo(originalContext);
		assertThat(intake.accept(low, firstDevice, first)).isEqualTo(saved);
		assertThat(jdbc.queryForObject("select evaluation->>'syntheticUnarchived' from matching where id=?",
			String.class, matching())).isEqualTo("true");
	}

	@Test
	void latePreviousGenerationResultCannotOverwriteTheReusedEncounter() throws Exception {
		intake.accept(low, firstDevice, first);
		intake.accept(high, secondDevice, second);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch released = new CountDownLatch(1);
		GATEWAY.set((input, hash) -> {
			entered.countDown();
			try {
				if (!released.await(15, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Synthetic evaluation timed out");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(exception);
			}
			return new EncounterEvaluationGateway.VerifiedResult(input.generationId(), hash,
				EncounterEvaluationGateway.Outcome.QUALIFIED, Map.of("synthetic", true));
		});
		long originalId = matching();
		try (var executor = Executors.newSingleThreadExecutor()) {
			var pending = executor.submit(() -> worker.run(originalId));
			try {
				assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
				clock.set(NOW.plusSeconds(8 * 86400));
				Submission next = nextSubmission(UUID.randomUUID(), (byte)1);
				when(submissions.prepare(low, firstDevice, next))
					.thenReturn(prepared(low, firstDevice, high, secondDevice, firstGeneration, next));
				intake.accept(low, firstDevice, next);
			} finally {
				released.countDown();
			}
			assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo(EncounterEvaluationWorker.State.STALE);
		}
		assertThat(work()).isNull();
		assertThat(jdbc.queryForObject("select evaluation is null from matching where id=?",
			Boolean.class, originalId)).isTrue();
	}

	private Submission nextSubmission(UUID event, byte proof) {
		return new Submission(UUID.randomUUID(), event, clock.instant(), "v1", "rule-1", UUID.randomUUID(),
			"synthetic-peer", new DeviceProof("synthetic", Base64.getEncoder().encodeToString(new byte[] {proof})));
	}

	private void register(Submission nextFirst, Submission nextSecond) throws Exception {
		when(submissions.prepare(low, firstDevice, nextFirst))
			.thenReturn(prepared(low, firstDevice, high, secondDevice, firstGeneration, nextFirst));
		when(submissions.prepare(high, secondDevice, nextSecond))
			.thenReturn(prepared(high, secondDevice, low, firstDevice, secondGeneration, nextSecond));
	}

	private String state(String key) {
		return jdbc.queryForObject("select request_state->>cast(? as text) from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, key, matching(), low);
	}

	static class MutableClock extends Clock {
		private volatile Instant current = NOW;

		void set(Instant value) {
			current = value;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return current;
		}
	}

	private VerifiedEncounterIntake intake(EncounterEvaluationWorker evaluator) {
		return new VerifiedEncounterIntake(jdbc, json, clock, submissions, inputs,
			receipts, evaluator, members, (policy, rule) -> new UUID(0, 77), transactions);
	}

	private Prepared prepared(long user, long device, long peer, long peerDevice, UUID generation,
		Submission submitted) throws Exception {
		String proof = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
			Base64.getDecoder().decode(submitted.evidence().payload())));
		Claims claims = new Claims(user, device, peer, peerDevice, submitted.submissionId(), submitted.eventId(),
			submitted.occurredAt(),
			"v1", "rule-1", submitted.settingsSnapshotId(), generation, 1, HASH, proof);
		ParticipantInput participant = new ParticipantInput(user, device, submitted.submissionId(), submitted.eventId(),
			receipts.canonicalHash(submitted), submitted.settingsSnapshotId(), 1, generation, HASH, proof);
		return new Prepared(claims, participant, new Snapshot(user, submitted.settingsSnapshotId(), HASH, 1L,
			Snapshot.sourceRef(user, 1)));
	}

	private Submission submission(UUID event, byte proof) {
		return new Submission(UUID.randomUUID(), event, NOW, "v1", "rule-1", UUID.randomUUID(), "synthetic-peer",
			new DeviceProof("synthetic", Base64.getEncoder().encodeToString(new byte[] {proof})));
	}

	private long user() {
		return jdbc.queryForObject("insert into users(provider,provider_user_id,status,created_at,signup_completed_at) "
			+ "values ('GOOGLE',?,'ACTIVE',?,?) returning id", Long.class, UUID.randomUUID().toString(),
			Timestamp.from(NOW), Timestamp.from(NOW));
	}

	private long device(long owner, UUID generation) {
		return jdbc.queryForObject("insert into keyring(serial_number,firmware_version,owner_user_id,state,"
			+ "owner_generation) values (?,'test',?,'REGISTERED',?) returning id", Long.class,
			UUID.randomUUID().toString(), owner, generation);
	}

	private int pairCount() {
		return jdbc.queryForObject("select count(*) from matching where user_low_id=? and user_high_id=?",
			Integer.class, low, high);
	}

	private long matching() {
		return jdbc.queryForObject("select id from matching where user_low_id=? and user_high_id=?",
			Long.class, low, high);
	}

	private String work() {
		return jdbc.queryForObject("select request_state->>cast(? as text) from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, EncounterEvaluationWorker.WORKFLOW_KEY,
			matching(), low);
	}

	private int receiptsCount() {
		return jdbc.queryForObject("select count(*) from matching_participant where matching_id=? "
			+ "and jsonb_exists(request_state,cast(? as text))", Integer.class, matching(),
			"encounter:verified-input");
	}
}
