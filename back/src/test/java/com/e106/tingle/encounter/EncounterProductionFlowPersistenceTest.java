package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.encounter.EncounterEvaluationGateway.Input;
import com.e106.tingle.encounter.EncounterEvaluationGateway.Outcome;
import com.e106.tingle.encounter.EncounterEvaluationGateway.ParticipantInput;
import com.e106.tingle.encounter.EncounterEvaluationGateway.VerifiedResult;
import com.e106.tingle.encounter.EncounterEvaluationWorker.State;
import com.e106.tingle.encounter.EncounterEvaluationWorker.Work;
import com.e106.tingle.encounter.EncounterReceipts.FrozenEvidence;
import com.e106.tingle.encounter.EncounterReceipts.StoredReceipt;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.keyring.proof.KeyringProofTranscript;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationStore;
import com.e106.tingle.proposal.evaluation.HistoricalEvidenceVerifier;
import com.e106.tingle.settings.snapshot.SettingsSnapshotJson;

import tools.jackson.databind.json.JsonMapper;

/** 실제 저장·워커·발행 흐름을 함께 검증하며 공식 기기·평가 제공자만 테스트 합성 구현으로 대체한다. */
@SpringBootTest(classes = EncounterProductionFlowPersistenceTest.Config.class, properties = {
	"spring.main.web-application-type=none", "spring.profiles.active=test",
	"spring.jpa.hibernate.ddl-auto=none", "spring.docker.compose.enabled=false",
	"spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:database/schema.sql",
	"app.encounter.evaluation.retry-seconds=30", "app.encounter.evaluation.enabled=true",
	"app.agreement.items[0].type=TERMS",
	"app.agreement.items[0].version=test", "app.agreement.items[0].required=true"
})
class EncounterProductionFlowPersistenceTest {
	private static final Instant NOW = Instant.parse("2026-10-29T04:00:00Z");
	private static final String HASH = "a".repeat(64);
	private static final Map<String, Object> EVALUATION = Map.of("matched", true, "ruleVersion", "rule-1");
	private static final AtomicReference<EncounterEvaluationGateway> GATEWAY = new AtomicReference<>();

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = MatchProposal.class)
	@EnableJpaRepositories(basePackageClasses = ProposalRepository.class)
	@Import({EncounterEvaluationWorker.class, EncounterProposalPublisher.class, EncounterReceipts.class,
		MemberAccessRepository.class, EncounterEvaluationCompletion.class, HistoricalEvaluationStore.class,
		StoredEncounterInputLookup.class, SettingsSnapshotJson.class})
	static class Config {
		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer("postgres:17");
		}

		@Bean
		MutableClock clock() {
			return new MutableClock();
		}

		@Bean
		EncounterEvaluationGateway syntheticGateway() {
			return (input, hash) -> GATEWAY.get().evaluate(input, hash);
		}
	}

	@Autowired
	private EncounterEvaluationWorker worker;
	@MockitoSpyBean
	private EncounterProposalPublisher publisher;
	@MockitoBean
	private HistoricalEvidenceVerifier verifier;
	@MockitoSpyBean
	private HistoricalEvaluationStore historical;
	@Autowired
	private EncounterReceipts receipts;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JsonMapper mapper;
	@Autowired
	private SettingsSnapshotJson snapshotJson;
	@Autowired
	private MutableClock clock;
	@Autowired
	private PlatformTransactionManager transactions;
	@Autowired
	private Environment environment;

	private Input input;
	private List<String> evidenceBefore;
	private Map<Long, StoredReceipt> stored;
	private Object lastPublication;

	@BeforeEach
	void prepareVerifiedDurableInputs() {
		clock.set(NOW);
		lastPublication = null;
		doAnswer(call -> {
			lastPublication = call.callRealMethod();
			return lastPublication;
		}).when(publisherTarget()).publish(anyLong(), any(), any(), anyString());
		GATEWAY.set(EncounterProductionFlowPersistenceTest::qualified);
		when(verifier.verify(any(), any())).thenReturn(true);
		long left = user();
		long right = user();
		long leftDevice = device(left);
		long rightDevice = device(right);
		long matching = jdbc.queryForObject("insert into matching(user_low_id,user_high_id,met_at,policy_version,"
			+ "opportunity_expires_at,counterpart_deadline_at,status) values (?,?,?,'v1',?,?,'WAITING') returning id",
			Long.class, left, right, Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(3600)),
			Timestamp.from(NOW.plusSeconds(1800)));
		jdbc.update("insert into matching_participant(matching_id,user_id,encounter_keyring_id,encounter_signature,"
			+ "encounter_reported_at,encounter_verified_at,request_state) "
			+ "values (?,?,?,decode('01','hex'),?,?,cast(? as jsonb)),"
			+ "(?,?,?,decode('02','hex'),?,?,cast(? as jsonb))", matching, left, leftDevice,
			Timestamp.from(NOW), Timestamp.from(NOW), "{\"safety:test\":\"preserved\"}", matching, right, rightDevice,
			Timestamp.from(NOW), Timestamp.from(NOW), "{\"decision:test\":\"preserved\"}");
		input = new Input(matching, UUID.randomUUID(), left, right, NOW,
			NOW.plusSeconds(3600), NOW.plusSeconds(1800), "v1", UUID.randomUUID(), "rule-1",
			participant(left, leftDevice, (byte)1), participant(right, rightDevice, (byte)2));
		stored = new LinkedHashMap<>();
		persistReceipts();
		evidenceBefore = evidence();
	}

	@Test
	void successfulEvaluationPublishesOnceAndPreservesDurableEvidenceOnReplay() {
		enqueue();
		assertThat(work().state()).isEqualTo(State.READY);
		assertThat(worker.run(input.matchingId())).isEqualTo(State.SUCCEEDED);
		assertPublishedAt(NOW);
		Work completed = work();
		String marker = proposalMarker();
		clock.set(NOW.plusSeconds(120));
		assertThat(worker.run(input.matchingId())).isEqualTo(State.IDLE);
		assertThat(inTransaction(() -> publisher.publish(input.matchingId(), input.generationId(),
			completed.attemptId(), completed.inputHash()))).contains(NOW);
		assertPublishedAt(NOW);
		assertThat(proposalMarker()).isEqualTo(marker);
		assertThat(work().attempts()).isEqualTo(1);
		assertEvidencePreserved();
	}

	@Test
	void matchingOffKeepsVerifiedEvaluationWithoutPublishing() {
		jdbc.update("update matching_preference set matching_enabled=false where user_id=?", input.rightUserId());
		enqueue();
		assertThat(worker.run(input.matchingId())).isEqualTo(State.SUCCEEDED);
		assertThat(work().state()).isEqualTo(State.SUCCEEDED);
		assertThat(jdbc.queryForObject("select status='WAITING' and proposed_at is null and raw_delete_at is null "
			+ "from matching where id=?", Boolean.class, input.matchingId())).isTrue();
		assertFinalEvaluation();
		assertThat(proposalMarker()).isNull();
		assertThat(mapper.readTree(state("encounter:evaluation:" + input.generationId())).get("qualified")
			.asBoolean()).isTrue();
		assertEvidencePreserved();
	}

	@Test
	void withheldProposalExpiresAtExactDeadlineWithoutChangingSuccessfulEvaluation() {
		AtomicInteger evaluations = new AtomicInteger();
		GATEWAY.set((captured, hash) -> {
			if (captured.matchingId() == input.matchingId()) {
				evaluations.incrementAndGet();
			}
			return qualified(captured, hash);
		});
		completeWithoutProposal();
		List<String> completed = successfulEvidence();
		clock.set(NOW.plusSeconds(1799));
		worker.poll();
		assertWaitingWithoutProposal();
		clock.set(NOW.plusSeconds(1800));
		worker.poll();
		assertExpiredAt(NOW.plusSeconds(1800));
		assertThat(successfulEvidence()).containsExactlyElementsOf(completed);
		assertThat(work().state()).isEqualTo(State.SUCCEEDED);
		assertThat(evaluations).hasValue(1);
		assertEvidencePreserved();
	}

	@Test
	void lateExpiryAfterRestartWithoutGatewayRetainsOriginalDeletionDeadlineOnRepeatedPolls() {
		completeWithoutProposal();
		List<String> completed = successfulEvidence();
		EncounterEvaluationWorker restarted = restartedWorker(environment);
		clock.set(NOW.plusSeconds(7200));
		restarted.poll();
		assertExpiredAt(NOW.plusSeconds(1800));
		clock.set(NOW.plusSeconds(10800));
		restarted.poll();
		assertThat(restarted.run(input.matchingId())).isEqualTo(State.IDLE);
		assertExpiredAt(NOW.plusSeconds(1800));
		assertThat(successfulEvidence()).containsExactlyElementsOf(completed);
		assertEvidencePreserved();
	}

	@Test
	void opportunityExpiryNeverChangesAnAlreadyPublishedProposal() {
		enqueue();
		assertThat(worker.run(input.matchingId())).isEqualTo(State.SUCCEEDED);
		List<String> completed = successfulEvidence();
		String marker = proposalMarker();
		clock.set(NOW.plusSeconds(7200));
		restartedWorker(environment).poll();
		worker.poll();
		assertPublishedAt(NOW);
		assertThat(proposalMarker()).isEqualTo(marker);
		assertThat(successfulEvidence()).containsExactlyElementsOf(completed);
		assertEvidencePreserved();
	}

	@Test
	void staleWorkflowCannotExpireCanonicalMatchingBeforeItsCurrentFixedDeadline() {
		completeWithoutProposal();
		List<String> completed = successfulEvidence();
		jdbc.update("update matching set met_at=?,policy_version='v2',opportunity_expires_at=?,"
			+ "counterpart_deadline_at=? where id=?", Timestamp.from(NOW.plusSeconds(60)),
			Timestamp.from(NOW.plusSeconds(7200)), Timestamp.from(NOW.plusSeconds(5400)), input.matchingId());
		clock.set(NOW.plusSeconds(1800));
		worker.poll();
		assertWaitingWithoutProposal();
		clock.set(NOW.plusSeconds(5400));
		worker.poll();
		assertExpiredAt(NOW.plusSeconds(5400));
		assertThat(successfulEvidence()).containsExactlyElementsOf(completed);
		assertEvidencePreserved();
	}

	@Test
	void failedExpiryTransactionRollsBackAndTheNextPollCanRetryWithoutReevaluating() {
		completeWithoutProposal();
		List<String> completed = successfulEvidence();
		String trigger = "test_encounter_expiry_" + input.matchingId();
		jdbc.execute("create function " + trigger + "() returns trigger language plpgsql as $$ begin "
			+ "raise exception 'synthetic encounter expiry failure'; return NEW; end; $$");
		try {
			jdbc.execute("create trigger " + trigger + " before update of status,raw_delete_at on matching "
				+ "for each row when (NEW.id=" + input.matchingId() + " and NEW.status='NOT_MATCHED') "
				+ "execute function " + trigger + "()");
			clock.set(NOW.plusSeconds(1800));
			worker.poll();
			assertWaitingWithoutProposal();
			assertThat(successfulEvidence()).containsExactlyElementsOf(completed);
		} finally {
			jdbc.execute("drop trigger if exists " + trigger + " on matching");
			jdbc.execute("drop function " + trigger + "()");
		}
		worker.poll();
		assertExpiredAt(NOW.plusSeconds(1800));
		assertThat(successfulEvidence()).containsExactlyElementsOf(completed);
		assertEvidencePreserved();
	}

	@Test
	void expiryDoesNotExtendAnExistingEarlierRawDeletionDeadline() {
		completeWithoutProposal();
		List<String> completed = successfulEvidence();
		Instant earlier = NOW.plus(Duration.ofDays(7));
		jdbc.update("update matching set raw_delete_at=? where id=?", Timestamp.from(earlier), input.matchingId());
		clock.set(NOW.plusSeconds(1800));
		worker.poll();
		assertThat(jdbc.queryForObject("select status='NOT_MATCHED' and proposed_at is null and raw_delete_at=? "
			+ "from matching where id=?", Boolean.class, Timestamp.from(earlier), input.matchingId())).isTrue();
		assertThat(successfulEvidence()).containsExactlyElementsOf(completed);
		assertEvidencePreserved();
	}

	@Test
	void disabledWorkerDoesNotRunTheExpirySweep() {
		completeWithoutProposal();
		List<String> completed = successfulEvidence();
		Environment disabled = mock(Environment.class);
		when(disabled.getProperty("app.encounter.evaluation.enabled", Boolean.class, false)).thenReturn(false);
		clock.set(NOW.plusSeconds(1800));
		restartedWorker(disabled).poll();
		assertWaitingWithoutProposal();
		assertThat(successfulEvidence()).containsExactlyElementsOf(completed);
		assertEvidencePreserved();
	}

	@Test
	void transientGatewayFailureRetriesThenPublishesExactlyOnce() {
		AtomicInteger calls = new AtomicInteger();
		GATEWAY.set((captured, hash) -> {
			if (calls.incrementAndGet() == 1) {
				throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
			}
			return qualified(captured, hash);
		});
		enqueue();
		assertThat(worker.run(input.matchingId())).isEqualTo(State.RETRY);
		Work retry = work();
		assertThat(retry.nextRetryAt()).isEqualTo(NOW.plusSeconds(30));
		assertThat(jdbc.queryForObject("select evaluation is null and proposed_at is null from matching where id=?",
			Boolean.class, input.matchingId())).isTrue();
		clock.set(NOW.plusSeconds(29));
		assertThat(worker.run(input.matchingId())).isEqualTo(State.IDLE);
		assertThat(calls).hasValue(1);
		clock.set(retry.nextRetryAt());
		assertThat(worker.run(input.matchingId())).isEqualTo(State.SUCCEEDED);
		assertThat(work().attemptId()).isNotEqualTo(retry.attemptId());
		assertThat(work().attempts()).isEqualTo(2);
		assertPublishedAt(NOW.plusSeconds(30));
		String marker = proposalMarker();
		clock.set(NOW.plusSeconds(90));
		assertThat(worker.run(input.matchingId())).isEqualTo(State.IDLE);
		assertThat(calls).hasValue(2);
		assertThat(proposalMarker()).isEqualTo(marker);
		assertPublishedAt(NOW.plusSeconds(30));
		assertEvidencePreserved();
	}

	@Test
	void completionFailureRollsBackEvaluationAndProposalBeforeRetry() {
		AtomicInteger publications = new AtomicInteger();
		doAnswer(call -> {
			Object result = call.callRealMethod();
			if (publications.incrementAndGet() == 1) {
				throw new IllegalStateException("synthetic post-publication failure");
			}
			return result;
		}).when(publisherTarget()).publish(anyLong(), any(), any(), anyString());
		enqueue();
		assertThat(worker.run(input.matchingId())).isEqualTo(State.RETRY);
		assertNoPartialCompletion();
		clock.set(NOW.plusSeconds(30));
		assertThat(worker.run(input.matchingId())).isEqualTo(State.SUCCEEDED);
		assertPublishedAt(NOW.plusSeconds(30));
		String first = proposalMarker();
		assertThat(worker.run(input.matchingId())).isEqualTo(State.IDLE);
		assertThat(proposalMarker()).isEqualTo(first);
		assertThat(publications).hasValue(2);
		assertEvidencePreserved();
	}

	@Test
	void permanentCompletionConflictRollsBackAndBlocksAutomaticRetry() {
		doAnswer(call -> {
			call.callRealMethod();
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}).when(publisherTarget()).publish(anyLong(), any(), any(), anyString());
		enqueue();
		assertThat(worker.run(input.matchingId())).isEqualTo(State.BLOCKED);
		assertNoPartialCompletion();
		clock.set(NOW.plusSeconds(30));
		assertThat(worker.run(input.matchingId())).isEqualTo(State.IDLE);
		assertEvidencePreserved();
	}

	@Test
	void absentHistoricalEvidenceCannotCompleteOrPublish() {
		GATEWAY.set((captured, hash) -> new VerifiedResult(captured.generationId(), hash,
			Outcome.QUALIFIED, EVALUATION));
		enqueue();
		assertThat(worker.run(input.matchingId())).isEqualTo(State.BLOCKED);
		assertNoPartialCompletion();
		verifyNoInteractions(historicalTarget());
	}

	@Test
	void rejectingHistoricalVerifierRollsBackTheDecisionBeforeRetry() {
		when(verifier.verify(any(), any())).thenReturn(false);
		enqueue();
		assertThat(worker.run(input.matchingId())).isEqualTo(State.RETRY);
		assertNoPartialCompletion();
		verify(publisherTarget(), never()).publish(anyLong(), any(), any(), anyString());
		when(verifier.verify(any(), any())).thenReturn(true);
		clock.set(NOW.plusSeconds(30));
		assertThat(worker.run(input.matchingId())).isEqualTo(State.SUCCEEDED);
		assertPublishedAt(NOW.plusSeconds(30));
	}

	@Test
	void historicalStoreFailureAfterCaptureLeavesNoPartialEvidence() {
		doAnswer(call -> {
			call.callRealMethod();
			throw new IllegalStateException("synthetic post-capture failure");
		}).when(historicalTarget()).store(any());
		enqueue();
		assertThat(worker.run(input.matchingId())).isEqualTo(State.RETRY);
		assertNoPartialCompletion();
		verify(publisherTarget(), never()).publish(anyLong(), any(), any(), anyString());
	}

	@Test
	void leaseExpiringDuringHistoricalVerificationRollsBackAllCompletionWrites() {
		when(verifier.verify(any(), any())).thenAnswer(call -> {
			clock.set(NOW.plusSeconds(61));
			return true;
		});
		enqueue();
		assertThat(worker.run(input.matchingId())).isEqualTo(State.STALE);
		assertNoPartialCompletion();
	}

	@Test
	void changedSnapshotBindingIsBlockedWithoutCallingTheHistoricalStore() {
		GATEWAY.set((captured, hash) -> {
			HistoricalEvaluationEvidence valid = evidence(captured);
			var wrong = new HistoricalEvaluationEvidence.Snapshot(valid.left().userId(), UUID.randomUUID(),
				valid.left().inputHash(), valid.left().settingsVersion(), valid.left().inputSourceRef());
			var changed = new HistoricalEvaluationEvidence(valid.schemaVersion(), valid.matchingId(), valid.metAt(),
				valid.policyVersion(), wrong, valid.right(), valid.manifest(), valid.scopeVersion(),
				valid.sourceEvidence(), valid.displayEvidenceIdsByViewer(), valid.conflictingSourceRefsByViewer());
			return new VerifiedResult(captured.generationId(), hash, Outcome.QUALIFIED, EVALUATION, changed);
		});
		enqueue();
		assertThat(worker.run(input.matchingId())).isEqualTo(State.BLOCKED);
		assertNoPartialCompletion();
		verifyNoInteractions(historicalTarget());
	}

	@ParameterizedTest
	@ValueSource(strings = {"owner", "device", "generation", "version", "state", "protocol",
		"ackRequest", "ackProof", "payloadHash", "payloadBytes", "inputHash", "inputJson",
		"lateAck", "earlyAck", "lateSnapshot"})
	void unrelatedOrCorruptRightStoredInputCannotCompleteOrPublish(String mutation) {
		ParticipantInput right = input.right();
		switch (mutation) {
			case "owner" -> changeRun("owner_user_id=?", input.leftUserId(), right.settingsSnapshotId());
			case "device" -> changeRun("keyring_id=?", input.left().deviceId(), right.settingsSnapshotId());
			case "generation" -> changeRun("owner_generation=?", UUID.randomUUID(), right.settingsSnapshotId());
			case "version" -> changeRun("settings_version=?", 2L, right.settingsSnapshotId());
			case "state" -> changeRun("state=?", "PENDING", right.settingsSnapshotId());
			case "protocol" -> changeRun("protocol_version=?", "unapproved-v1", right.settingsSnapshotId());
			case "ackRequest" -> jdbc.update("update keyring_sync_run set ack_request_key=null "
				+ "where sync_run_id=?", right.settingsSnapshotId());
			case "ackProof" -> changeRun("ack_proof_hash=?", "not-a-hash", right.settingsSnapshotId());
			case "payloadHash" -> changeRun("payload_hash=?", "f".repeat(64), right.settingsSnapshotId());
			case "payloadBytes" -> changeRun("payload=?", new byte[] {99}, right.settingsSnapshotId());
			case "inputHash" -> jdbc.update("update settings_snapshot set input_hash=? "
				+ "where user_id=? and settings_version=?", "f".repeat(64), right.userId(), right.settingsVersion());
			case "inputJson" -> jdbc.update("update settings_snapshot set input_json=? "
				+ "where user_id=? and settings_version=?", "{\"tampered\":true}",
				right.userId(), right.settingsVersion());
			case "lateAck" -> changeRun("acked_at=?", Timestamp.from(NOW.plusSeconds(1)),
				right.settingsSnapshotId());
			case "earlyAck" -> changeRun("acked_at=?", Timestamp.from(NOW.minusSeconds(61)),
				right.settingsSnapshotId());
			case "lateSnapshot" -> jdbc.update("update settings_snapshot set captured_at=? "
				+ "where user_id=? and settings_version=?", Timestamp.from(NOW.minusSeconds(59)),
				right.userId(), right.settingsVersion());
			default -> throw new IllegalArgumentException("Unknown synthetic mutation");
		}
		enqueue();

		assertThat(worker.run(input.matchingId())).isEqualTo(State.BLOCKED);
		assertNoPartialCompletion();
		verifyNoInteractions(historicalTarget());
		verify(publisherTarget(), never()).publish(anyLong(), any(), any(), anyString());
		assertEvidencePreserved();
	}

	@Test
	void storedSnapshotAndAckAtTheEncounterBoundaryCanComplete() {
		for (ParticipantInput participant : new ParticipantInput[] {input.left(), input.right()}) {
			jdbc.update("update keyring_sync_run set created_at=?,acked_at=? where sync_run_id=?",
				Timestamp.from(NOW), Timestamp.from(NOW), participant.settingsSnapshotId());
			jdbc.update("update settings_snapshot set captured_at=? where user_id=? and settings_version=?",
				Timestamp.from(NOW), participant.userId(), participant.settingsVersion());
		}
		enqueue();

		assertThat(worker.run(input.matchingId())).isEqualTo(State.SUCCEEDED);
		assertPublishedAt(NOW);
		assertEvidencePreserved();
	}

	private void changeRun(String assignment, Object value, UUID runId) {
		jdbc.update("update keyring_sync_run set " + assignment + " where sync_run_id=?", value, runId);
	}

	private void assertNoPartialCompletion() {
		assertThat(jdbc.queryForObject("select status='WAITING' and proposed_at is null "
			+ "and raw_delete_at is null and evaluation is null from matching where id=?",
			Boolean.class, input.matchingId())).isTrue();
		assertThat(state("encounter:evaluation:" + input.generationId())).isNull();
		assertThat(proposalMarker()).isNull();
	}

	private void completeWithoutProposal() {
		jdbc.update("update matching_preference set matching_enabled=false where user_id=?", input.rightUserId());
		enqueue();
		assertThat(worker.run(input.matchingId())).isEqualTo(State.SUCCEEDED);
		assertFinalEvaluation();
		assertWaitingWithoutProposal();
	}

	private void assertWaitingWithoutProposal() {
		assertThat(jdbc.queryForObject("select status='WAITING' and proposed_at is null and raw_delete_at is null "
			+ "from matching where id=?", Boolean.class, input.matchingId())).isTrue();
		assertThat(proposalMarker()).isNull();
	}

	private void assertExpiredAt(Instant deadline) {
		assertThat(jdbc.queryForObject("select status='NOT_MATCHED' and proposed_at is null and raw_delete_at=? "
			+ "from matching where id=?", Boolean.class,
			Timestamp.from(deadline.plus(Duration.ofDays(14))), input.matchingId())).isTrue();
		assertThat(proposalMarker()).isNull();
	}

	private List<String> successfulEvidence() {
		return List.of(state(EncounterEvaluationWorker.WORKFLOW_KEY),
			state("encounter:evaluation:" + input.generationId()),
			jdbc.queryForObject("select evaluation::text from matching where id=?", String.class, input.matchingId()));
	}

	@SuppressWarnings("unchecked")
	private EncounterEvaluationWorker restartedWorker(Environment settings) {
		return new EncounterEvaluationWorker(jdbc, mapper, clock, receipts, mock(ObjectProvider.class),
			mock(ObjectProvider.class), mock(ObjectProvider.class), settings, transactions);
	}

	private void assertPublishedAt(Instant first) {
		Map<String, Object> actual = jdbc.queryForMap("select status::text,proposed_at,raw_delete_at,"
			+ "opportunity_expires_at,counterpart_deadline_at,legacy_state from matching where id=?",
			input.matchingId());
		assertThat(jdbc.queryForObject("select status='PENDING' and proposed_at=? and raw_delete_at=? "
			+ "and opportunity_expires_at=? and counterpart_deadline_at=? from matching where id=?", Boolean.class,
			Timestamp.from(first), Timestamp.from(first.plus(Duration.ofDays(14))),
			Timestamp.from(NOW.plusSeconds(3600)),
			Timestamp.from(NOW.plusSeconds(1800)), input.matchingId()))
			.as("stored matching=%s; publisher=%s; marker=%s", actual, lastPublication, proposalMarker()).isTrue();
		assertFinalEvaluation();
		assertThat(proposalMarker()).isNotNull();
		assertThat(jdbc.queryForObject("select count(*) from chat where matching_id=?", Integer.class,
			input.matchingId())).isZero();
	}

	private EncounterProposalPublisher publisherTarget() {
		return AopTestUtils.getUltimateTargetObject(publisher);
	}

	private HistoricalEvaluationStore historicalTarget() {
		return AopTestUtils.getUltimateTargetObject(historical);
	}

	private void assertFinalEvaluation() {
		String evaluation = jdbc.queryForObject("select evaluation::text from matching where id=?",
			String.class, input.matchingId());
		var actual = mapper.readTree(evaluation);
		assertThat(actual.get("matched").asBoolean()).isTrue();
		assertThat(actual.has("historicalEvidence")).isTrue();
		assertThat(actual).isEqualTo(mapper.readTree(state("encounter:evaluation:" + input.generationId()))
			.get("evaluation"));
		assertThat(historical.find(input.matchingId())).contains(evidence(input));
	}

	private static VerifiedResult qualified(Input captured, String hash) {
		return new VerifiedResult(captured.generationId(), hash, Outcome.QUALIFIED, EVALUATION, evidence(captured));
	}

	private static HistoricalEvaluationEvidence evidence(Input captured) {
		Map<String, String> manifest = new LinkedHashMap<>();
		for (String key : List.of("surveyVersion", "wordingVersion", "scoringVersion", "typeModelVersion",
			"analysisVersion", "typeReferenceVersion", "engineVersion")) {
			manifest.put(key, "synthetic-v1");
		}
		manifest.put("releaseId", captured.releaseId().toString());
		manifest.put("matchingVersion", captured.matchingRuleVersion());
		manifest.put("productPolicyVersion", captured.policyVersion());
		manifest.put("contentHash", HASH);
		return new HistoricalEvaluationEvidence(1, captured.matchingId(), captured.metAt(), captured.policyVersion(),
			snapshot(captured.left()), snapshot(captured.right()), manifest, "synthetic-scope-v1", List.of(),
			Map.of("A", List.of(), "B", List.of()), Map.of("A", List.of(), "B", List.of()));
	}

	private static HistoricalEvaluationEvidence.Snapshot snapshot(ParticipantInput participant) {
		return new HistoricalEvaluationEvidence.Snapshot(participant.userId(), participant.settingsSnapshotId(),
			participant.inputHash(), participant.settingsVersion(),
			HistoricalEvaluationEvidence.Snapshot.sourceRef(participant.userId(), participant.settingsVersion()));
	}

	private void assertEvidencePreserved() {
		assertThat(evidence()).containsExactlyElementsOf(evidenceBefore);
		assertThat(state("safety:test")).isEqualTo("preserved");
		assertThat(jdbc.queryForObject("select request_state->>'decision:test' from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, input.matchingId(), input.rightUserId()))
			.isEqualTo("preserved");
		inTransaction(() -> {
			for (ParticipantInput participant : new ParticipantInput[] {input.left(), input.right()}) {
				assertThat(receipts.find(participant.userId(), participant.deviceId(), participant.submissionId(),
					participant.eventId(), participant.requestHash())).contains(stored.get(participant.userId()));
			}
			return null;
		});
	}

	private List<String> evidence() {
		return jdbc.query("select jsonb_build_array(matching_id,user_id,encounter_keyring_id,"
			+ "encode(encounter_signature,'hex'),encounter_reported_at,encounter_verified_at,response,"
			+ "responded_at,blocked_at,decision_request_key)::text from matching_participant "
			+ "where matching_id=? order by user_id", (rs, row) -> rs.getString(1), input.matchingId());
	}

	private ParticipantInput participant(long userId, long deviceId, byte signature) {
		try {
			String proofHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(new byte[] {signature}));
			UUID runId = UUID.randomUUID();
			UUID ownerGeneration = jdbc.queryForObject("select owner_generation from keyring where id=?",
				UUID.class, deviceId);
			String canonical = snapshotJson.canonicalize(mapper.writeValueAsString(Map.of(
				"synthetic", true, "userId", userId, "settingsVersion", 1)));
			String inputHash = snapshotJson.hash(canonical);
			jdbc.update("insert into settings_snapshot(user_id,settings_version,input_format,input_json,"
				+ "input_hash,captured_at) values (?,1,'settings-source-evidence-v1',?,?,?)",
				userId, canonical, inputHash, Timestamp.from(NOW.minusSeconds(120)));
			// 저장 관계만 검증할 합성 ACK다. 공식 기기 서명이나 payload codec의 성공 증거가 아니다.
			byte[] payload = new byte[] {signature, 42};
			String payloadHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
			jdbc.update("insert into keyring_sync_run(sync_run_id,keyring_id,owner_user_id,owner_generation,"
				+ "credential_key_id,protocol_version,settings_version,payload_format,payload,payload_hash,nonce,"
				+ "created_at,state,acked_at,ack_proof_hash,request_key,ack_request_key,revision) "
				+ "values (?,?,?,?,'synthetic-key',?,1,'synthetic-fixture-v1',?,?,?,?,'ACKED',?,?,?,?,0)",
				runId, deviceId, userId, ownerGeneration, KeyringProofTranscript.PROTOCOL_VERSION, payload,
				payloadHash, "A".repeat(43), Timestamp.from(NOW.minusSeconds(60)),
				Timestamp.from(NOW.minusSeconds(30)), HASH, UUID.randomUUID(), UUID.randomUUID());
			return new ParticipantInput(userId, deviceId, UUID.randomUUID(), UUID.randomUUID(), HASH,
				runId, 1, ownerGeneration, inputHash, proofHash);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private void persistReceipts() {
		inTransaction(() -> {
			for (ParticipantInput participant : new ParticipantInput[] {input.left(), input.right()}) {
				UUID receiptId = UUID.randomUUID();
				Map<String, Object> response = new LinkedHashMap<>();
				response.put("submissionId", participant.submissionId());
				response.put("eventId", participant.eventId());
				response.put("receiptId", receiptId);
				response.put("receiptStatus", "STORED");
				response.put("canDeleteLocalRecord", true);
				response.put("encounterStatus", "WAITING");
				response.put("counterpartDeadlineAt", NOW.plusSeconds(1800));
				response.put("retryable", false);
				response.put("errorCode", null);
				FrozenEvidence evidence = new FrozenEvidence(participant.ownerGeneration(),
					participant.settingsVersion(), participant.settingsSnapshotId(), participant.inputHash(),
					participant.proofHash(), input.policyVersion(), input.matchingRuleVersion());
				StoredReceipt receipt = new StoredReceipt(receiptId, NOW, input.generationId(), evidence,
					mapper.writeValueAsString(response));
				receipts.save(input.matchingId(), participant.userId(), participant.deviceId(),
					participant.submissionId(), participant.eventId(), participant.requestHash(), receipt);
				stored.put(participant.userId(), receipt);
			}
			return null;
		});
	}

	private long user() {
		long id = jdbc.queryForObject("insert into users(provider,provider_user_id,status,"
			+ "signup_completed_at,created_at) "
			+ "values ('GOOGLE',?,'ACTIVE',?,?) returning id", Long.class, UUID.randomUUID().toString(),
			Timestamp.from(NOW.minusSeconds(3600)), Timestamp.from(NOW.minusSeconds(3600)));
		jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at) "
			+ "values (?,'TERMS','test',true,?)", id, Timestamp.from(NOW.minusSeconds(3600)));
		jdbc.update("insert into matching_preference(user_id,matching_enabled,age_preference_mode,photo_required) "
			+ "values (?,true,false,false)", id);
		jdbc.update("insert into user_setting(user_id,vibration_enabled,led_enabled,settings_version) "
			+ "values (?,true,true,1)", id);
		return id;
	}

	private long device(long owner) {
		return jdbc.queryForObject("insert into keyring(serial_number,firmware_version,owner_user_id,state,"
			+ "owner_generation,synced_settings_version,synced_at) "
			+ "values (?,'test',?,'REGISTERED',?,1,?) returning id", Long.class,
			UUID.randomUUID().toString(), owner, UUID.randomUUID(), Timestamp.from(NOW));
	}

	private void enqueue() {
		inTransaction(() -> {
			worker.enqueue(input);
			return null;
		});
	}

	private Work work() {
		return mapper.readValue(state(EncounterEvaluationWorker.WORKFLOW_KEY), Work.class);
	}

	private String proposalMarker() {
		return state("encounter:proposal:" + input.generationId());
	}

	private String state(String key) {
		return jdbc.queryForObject("select request_state->>cast(? as text) from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, key, input.matchingId(), input.leftUserId());
	}

	private <T> T inTransaction(Supplier<T> action) {
		return new TransactionTemplate(transactions).execute(status -> action.get());
	}

	static class MutableClock extends Clock {
		private final AtomicReference<Instant> value = new AtomicReference<>(NOW);

		void set(Instant instant) {
			value.set(instant);
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return value.get();
		}
	}
}
