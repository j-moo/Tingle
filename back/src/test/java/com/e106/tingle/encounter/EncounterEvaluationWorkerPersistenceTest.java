package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.SharedPostgres;
import com.e106.tingle.auth.MemberAccessRepository;
import com.e106.tingle.encounter.EncounterEvaluationGateway.Input;
import com.e106.tingle.encounter.EncounterEvaluationGateway.Outcome;
import com.e106.tingle.encounter.EncounterEvaluationGateway.ParticipantInput;
import com.e106.tingle.encounter.EncounterEvaluationGateway.VerifiedResult;
import com.e106.tingle.encounter.EncounterEvaluationWorker.State;
import com.e106.tingle.encounter.EncounterEvaluationWorker.Work;
import com.e106.tingle.encounter.EncounterReceipts.FrozenEvidence;
import com.e106.tingle.encounter.EncounterReceipts.StoredReceipt;
import com.e106.tingle.global.ai.AiCallException;

import tools.jackson.databind.json.JsonMapper;

/** 합성 검증기만 테스트에 주입한다. 공식 기기/AI 연동 증거가 아니다. */
@SpringBootTest(classes = EncounterEvaluationWorkerPersistenceTest.Config.class, properties = {
	"spring.main.web-application-type=none", "spring.profiles.active=test",
	"spring.jpa.hibernate.ddl-auto=none", "spring.docker.compose.enabled=false",
	"spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:database/schema.sql",
	"app.encounter.evaluation.enabled=true"
})
class EncounterEvaluationWorkerPersistenceTest {
	private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
	private static final String HASH = "a".repeat(64);
	private static final AtomicReference<EncounterEvaluationGateway> GATEWAY = new AtomicReference<>();

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@Import({EncounterReceipts.class, ReligionEvidenceRetirement.class})
	static class Config {
		@Bean
		JdbcConnectionDetails postgresConnection() {
			return SharedPostgres.newDatabase();
		}

		@Bean
		MutableClock clock() {
			return new MutableClock();
		}

		@Bean
		@SuppressWarnings("unchecked")
		EncounterEvaluationWorker worker(JdbcTemplate jdbc, JsonMapper mapper, MutableClock clock,
			EncounterReceipts receipts, Environment environment, PlatformTransactionManager transactions) {
			ObjectProvider<EncounterEvaluationGateway> gateways = mock(ObjectProvider.class);
			when(gateways.getIfAvailable()).thenAnswer(invocation -> GATEWAY.get());
			EncounterEvaluationCompletion completion = mock(EncounterEvaluationCompletion.class);
			when(completion.complete(any(), any())).thenAnswer(invocation ->
				invocation.<VerifiedResult>getArgument(1).evaluation());
			ObjectProvider<EncounterEvaluationCompletion> completions = mock(ObjectProvider.class);
			when(completions.getIfAvailable()).thenReturn(completion);
			return new EncounterEvaluationWorker(jdbc, mapper, clock, receipts, gateways,
				mock(ObjectProvider.class), completions, environment, transactions,
				new MemberAccessRepository(jdbc, environment));
		}
	}

	@Autowired
	private EncounterEvaluationWorker worker;
	@Autowired
	private EncounterReceipts receipts;
	@Autowired
	private ReligionEvidenceRetirement retirement;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JsonMapper mapper;
	@Autowired
	private MutableClock clock;
	@Autowired
	private PlatformTransactionManager transactions;

	private Input input;

	@BeforeEach
	void isolateDisposableDatabaseFixtures() {
		jdbc.update("delete from matching_participant");
		jdbc.update("delete from matching");
		prepareVerifiedSyntheticInputs();
	}

	void prepareVerifiedSyntheticInputs() {
		clock.set(NOW);
		GATEWAY.set(null);
		long left = user();
		long right = user();
		long leftDevice = keyring(left);
		long rightDevice = keyring(right);
		long matching = jdbc.queryForObject("insert into matching(user_low_id,user_high_id,met_at,policy_version,"
			+ "opportunity_expires_at,counterpart_deadline_at,status) values (?,?,?,'v1',?,?,'WAITING') returning id",
			Long.class, left, right, Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(3600)),
			Timestamp.from(NOW.plusSeconds(1800)));
		jdbc.update("insert into matching_participant(matching_id,user_id,encounter_keyring_id,encounter_signature,"
			+ "encounter_reported_at,encounter_verified_at) values (?,?,?,decode('01','hex'),?,?),"
			+ "(?,?,?,decode('02','hex'),?,?)", matching, left, leftDevice, Timestamp.from(NOW), Timestamp.from(NOW),
			matching, right, rightDevice, Timestamp.from(NOW), Timestamp.from(NOW));
		input = new Input(matching, UUID.randomUUID(), left, right, NOW, NOW.plusSeconds(3600),
			NOW.plusSeconds(1800), "v1", UUID.randomUUID(), "rule-1",
			participant(left, leftDevice, (byte)1), participant(right, rightDevice, (byte)2));
		persistReceipts(input);
	}

	@Test
	void missingGatewayIsBlockedWithoutEvaluationOrProposal() {
		enqueue(input);
		assertThat(worker.run(input.matchingId())).isEqualTo(State.BLOCKED);
		assertThat(jdbc.queryForObject("select proposed_at is null and evaluation is null from matching where id=?",
			Boolean.class, input.matchingId())).isTrue();
		assertThat(work().attempts()).isZero();
	}

	@Test
	void unresolvedOfficialContractIsBlockedWithoutAutomaticRetry() {
		AtomicInteger calls = new AtomicInteger();
		GATEWAY.set((captured, hash) -> {
			calls.incrementAndGet();
			return new VerifiedResult(captured.generationId(), hash, Outcome.UNRESOLVED, Map.of());
		});
		enqueue(input);
		assertThat(worker.run(input.matchingId())).isEqualTo(State.BLOCKED);
		clock.set(NOW.plusSeconds(120));
		assertThat(worker.run(input.matchingId())).isEqualTo(State.IDLE);
		assertThat(calls).hasValue(1);
	}

	@Test
	void terminalHttpContractFailureBlocksTheGenerationWithoutAutomaticRetry() {
		AtomicInteger calls = new AtomicInteger();
		GATEWAY.set((captured, hash) -> {
			calls.incrementAndGet();
			throw new AiCallException(false, 409);
		});
		enqueue(input);
		assertThat(worker.run(input.matchingId())).isEqualTo(State.BLOCKED);
		clock.set(NOW.plusSeconds(60));
		worker.poll();
		assertThat(worker.run(input.matchingId())).isEqualTo(State.IDLE);
		assertThat(calls).hasValue(1);
		assertThat(work().attempts()).isEqualTo(1);
		assertThat(work().nextRetryAt()).isNull();
		assertThat(jdbc.queryForObject("select evaluation is null from matching where id=?",
			Boolean.class, input.matchingId())).isTrue();
	}

	@Test
	void retryableHttpFailureKeepsTheDurableRetryScheduleWithoutSavingAnEvaluation() {
		GATEWAY.set((captured, hash) -> {
			throw new AiCallException(true, 503);
		});
		enqueue(input);
		assertThat(worker.run(input.matchingId())).isEqualTo(State.RETRY);
		assertThat(work().attempts()).isEqualTo(1);
		assertThat(work().nextRetryAt()).isAfter(NOW);
		assertThat(jdbc.queryForObject("select evaluation is null from matching where id=?",
			Boolean.class, input.matchingId())).isTrue();
	}

	@Test
	void futureRetriesBeforeThePageLimitCannotStarveAReadyGeneration() {
		for (int index = 0; index < 26; index++) {
			enqueue(input);
			Work queued = work();
			Work future = new Work(queued.generationId(), UUID.randomUUID(), queued.inputHash(), State.RETRY,
				1, null, NOW.plusSeconds(900), queued.input());
			jdbc.update("update matching_participant set request_state=request_state "
				+ "|| jsonb_build_object(cast(? as text),cast(? as text)) where matching_id=? and user_id=?",
				EncounterEvaluationWorker.WORKFLOW_KEY, mapper.writeValueAsString(future),
				input.matchingId(), input.leftUserId());
			prepareVerifiedSyntheticInputs();
		}
		enqueue(input);
		AtomicInteger calls = new AtomicInteger();
		GATEWAY.set((captured, hash) -> {
			calls.incrementAndGet();
			return qualified(captured, hash);
		});
		worker.poll();
		assertThat(work().state()).isEqualTo(State.SUCCEEDED);
		assertThat(calls).hasValue(1);
		assertThat(jdbc.queryForObject("select count(*) from matching_participant "
			+ "where cast(request_state->>? as jsonb)->>'state'='RETRY'", Integer.class,
			EncounterEvaluationWorker.WORKFLOW_KEY)).isEqualTo(26);
	}

	@Test
	void qualifiedResultIsStoredOnceOutsideExternalTransactionAndPreservesReceipts() {
		AtomicInteger calls = new AtomicInteger();
		GATEWAY.set((captured, hash) -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			calls.incrementAndGet();
			return qualified(captured, hash);
		});
		jdbc.update("update matching_participant set request_state=request_state || "
			+ "'{\"safety:test\":\"keep\",\"command\":\"ACCEPT\"}'::jsonb where matching_id=? and user_id=?",
			input.matchingId(), input.leftUserId());
		enqueue(input);
		assertThat(worker.run(input.matchingId())).isEqualTo(State.SUCCEEDED);
		assertThat(worker.run(input.matchingId())).isEqualTo(State.IDLE);
		assertThat(calls).hasValue(1);
		String state = jdbc.queryForObject("select request_state::text from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, input.matchingId(), input.leftUserId());
		assertThat(state).contains("safety:test", "ACCEPT", "encounter:submission:", "encounter:evaluation:");
		assertThat(work().state()).isEqualTo(State.SUCCEEDED);
	}

	@Test
	void restartRetainsRetryScheduleAndInputHash() {
		GATEWAY.set((captured, hash) -> {
			throw new IllegalStateException("test-only transient failure");
		});
		enqueue(input);
		assertThat(worker.run(input.matchingId())).isEqualTo(State.RETRY);
		Work first = work();
		assertThat(first.nextRetryAt()).isEqualTo(NOW.plusSeconds(30));
		assertThat(worker.run(input.matchingId())).isEqualTo(State.IDLE);
		clock.set(NOW.plusSeconds(30));
		GATEWAY.set(this::qualified);
		assertThat(worker.run(input.matchingId())).isEqualTo(State.SUCCEEDED);
		assertThat(work().inputHash()).isEqualTo(first.inputHash());
		assertThat(work().attempts()).isEqualTo(2);
	}

	@Test
	void retryBudgetDoesNotPublishAProposal() {
		GATEWAY.set((captured, hash) -> {
			throw new IllegalStateException("test-only transient failure");
		});
		enqueue(input);
		assertThat(worker.run(input.matchingId())).isEqualTo(State.RETRY);
		clock.set(NOW.plusSeconds(30));
		assertThat(worker.run(input.matchingId())).isEqualTo(State.RETRY);
		clock.set(NOW.plusSeconds(60));
		assertThat(worker.run(input.matchingId())).isEqualTo(State.FAILED);
		assertThat(work().attempts()).isEqualTo(3);
		assertThat(jdbc.queryForObject("select proposed_at from matching where id=?", Timestamp.class,
			input.matchingId())).isNull();
	}

	@Test
	void exactDeadlineRejectsWorkBeforeCallingGateway() {
		AtomicInteger calls = new AtomicInteger();
		GATEWAY.set((captured, hash) -> {
			calls.incrementAndGet();
			return qualified(captured, hash);
		});
		enqueue(input);
		clock.set(NOW.plusSeconds(1800));
		assertThat(worker.run(input.matchingId())).isEqualTo(State.EXPIRED);
		assertThat(calls).hasValue(0);
	}

	@Test
	void signatureChangedDuringExternalEvaluationCannotBeStored() {
		GATEWAY.set((captured, hash) -> {
			jdbc.update("update matching_participant set encounter_signature=decode('03','hex') "
				+ "where matching_id=? and user_id=?", captured.matchingId(), captured.leftUserId());
			return qualified(captured, hash);
		});
		enqueue(input);
		assertThat(worker.run(input.matchingId())).isEqualTo(State.STALE);
		assertThat(jdbc.queryForObject("select evaluation is null and proposed_at is null from matching where id=?",
			Boolean.class, input.matchingId())).isTrue();
	}

	@Test
	void religionWithdrawalDuringExternalEvaluationCannotRestoreRetiredEvidence() {
		jdbc.update("insert into settings_snapshot(user_id,settings_version,input_format,input_json,input_hash,"
			+ "captured_at,redacted_at,redaction_reason) values (?,1,'test','{}',?,?,?,'PROFILE_RELIGION_WITHDRAWN')",
			input.rightUserId(), "0".repeat(64), Timestamp.from(NOW), Timestamp.from(NOW));
		GATEWAY.set((captured, hash) -> {
			retirement.retire(captured.rightUserId());
			return qualified(captured, hash);
		});
		enqueue(input);

		assertThat(worker.run(input.matchingId())).isEqualTo(State.STALE);
		assertThat(worker.run(input.matchingId())).isEqualTo(State.IDLE);
		assertThat(jdbc.queryForObject("select evaluation is null and proposed_at is null from matching where id=?",
			Boolean.class, input.matchingId())).isTrue();
		for (String state : jdbc.queryForList("select request_state::text from matching_participant "
			+ "where matching_id=?", String.class, input.matchingId())) {
			assertThat(state).doesNotContain(HASH, EncounterEvaluationWorker.WORKFLOW_KEY)
				.contains("encounter:retired:");
		}
	}

	@Test
	void transferredKeyringIsRejectedBeforeCallingGateway() {
		enqueue(input);
		jdbc.update("update keyring set owner_user_id=? where id=?", input.rightUserId(), input.left().deviceId());
		AtomicInteger calls = new AtomicInteger();
		GATEWAY.set((captured, hash) -> {
			calls.incrementAndGet();
			return qualified(captured, hash);
		});
		assertThat(worker.run(input.matchingId())).isEqualTo(State.STALE);
		assertThat(calls).hasValue(0);
	}

	@Test
	void extendedDeadlineRequiresANewGeneration() {
		enqueue(input);
		jdbc.update("update matching set counterpart_deadline_at=? where id=?",
			Timestamp.from(NOW.plusSeconds(2000)), input.matchingId());
		GATEWAY.set((captured, hash) -> {
			throw new AssertionError("Changed immutable deadline must not reach the gateway");
		});
		assertThat(worker.run(input.matchingId())).isEqualTo(State.STALE);
		assertThat(jdbc.queryForObject("select status::text from matching where id=?", String.class,
			input.matchingId())).isEqualTo("WAITING");
	}

	@Test
	void futureVerificationTimeCannotBeEnqueued() {
		jdbc.update("update matching_participant set encounter_verified_at=? where matching_id=? and user_id=?",
			Timestamp.from(NOW.plusSeconds(1)), input.matchingId(), input.leftUserId());
		assertThatThrownBy(() -> enqueue(input)).isInstanceOf(RuntimeException.class);
	}

	@Test
	void concurrentClaimAndOldLeaseResultCannotOverwriteTheNewAttempt() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		GATEWAY.set((captured, hash) -> {
			entered.countDown();
			await(release);
			return qualified(captured, hash);
		});
		enqueue(input);
		try (var pool = Executors.newSingleThreadExecutor()) {
			var old = pool.submit(() -> worker.run(input.matchingId()));
			assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(worker.run(input.matchingId())).isEqualTo(State.IDLE);
			UUID oldAttempt = work().attemptId();
			clock.set(NOW.plusSeconds(60));
			GATEWAY.set(this::qualified);
			assertThat(worker.run(input.matchingId())).isEqualTo(State.SUCCEEDED);
			UUID winningAttempt = work().attemptId();
			assertThat(winningAttempt).isNotEqualTo(oldAttempt);
			release.countDown();
			assertThat(old.get(10, TimeUnit.SECONDS)).isEqualTo(State.STALE);
			assertThat(work().attemptId()).isEqualTo(winningAttempt);
		} finally {
			release.countDown();
		}
	}

	@Test
	void oldGenerationResultCannotOverwriteReusedWaitingRow() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		GATEWAY.set((captured, hash) -> {
			entered.countDown();
			await(release);
			return qualified(captured, hash);
		});
		enqueue(input);
		try (var pool = Executors.newSingleThreadExecutor()) {
			var old = pool.submit(() -> worker.run(input.matchingId()));
			assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
			Input replacement = new Input(input.matchingId(), UUID.randomUUID(), input.leftUserId(),
				input.rightUserId(), NOW, input.opportunityExpiresAt(), input.counterpartDeadlineAt(),
				"v1", input.releaseId(), "rule-1",
				participant(input.leftUserId(), input.left().deviceId(), (byte)1),
				participant(input.rightUserId(), input.right().deviceId(), (byte)2));
			persistReceipts(replacement);
			enqueue(replacement);
			release.countDown();
			assertThat(old.get(10, TimeUnit.SECONDS)).isEqualTo(State.STALE);
			assertThat(work().generationId()).isEqualTo(replacement.generationId());
			assertThat(work().state()).isEqualTo(State.READY);
		} finally {
			release.countDown();
		}
	}

	@Test
	void missingOrConflictingDurableReceiptCannotBeEnqueued() {
		jdbc.update("update matching_participant set request_state='{}'::jsonb where matching_id=? and user_id=?",
			input.matchingId(), input.rightUserId());
		assertThatThrownBy(() -> enqueue(input)).isInstanceOf(RuntimeException.class);
		assertThat(jdbc.queryForObject("select request_state->>? from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, EncounterEvaluationWorker.WORKFLOW_KEY,
			input.matchingId(), input.leftUserId())).isNull();
	}

	@Test
	void resultForAnotherInputIsRetriedWithoutSavingAnEvaluation() {
		GATEWAY.set((captured, hash) -> new VerifiedResult(captured.generationId(), "b".repeat(64),
			Outcome.QUALIFIED, Map.of("matched", true)));
		enqueue(input);
		assertThat(worker.run(input.matchingId())).isEqualTo(State.RETRY);
		assertThat(jdbc.queryForObject("select evaluation is null from matching where id=?", Boolean.class,
			input.matchingId())).isTrue();
	}

	private VerifiedResult qualified(Input captured, String hash) {
		return new VerifiedResult(captured.generationId(), hash, Outcome.QUALIFIED,
			Map.of("matched", true, "releaseId", captured.releaseId().toString()));
	}

	private ParticipantInput participant(long userId, long deviceId, byte signature) {
		return new ParticipantInput(userId, deviceId, UUID.randomUUID(), UUID.randomUUID(), HASH,
			UUID.randomUUID(), 1, UUID.randomUUID(), HASH, fingerprint(signature));
	}

	private void persistReceipts(Input captured) {
		inTransaction(() -> {
			for (ParticipantInput participant : new ParticipantInput[] {captured.left(), captured.right()}) {
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
					participant.settingsVersion(),
					participant.settingsSnapshotId(), participant.inputHash(), participant.proofHash(), "v1", "rule-1");
				receipts.save(captured.matchingId(), participant.userId(), participant.deviceId(),
					participant.submissionId(),
					participant.eventId(), participant.requestHash(), new StoredReceipt(receiptId, NOW,
						captured.generationId(), evidence, mapper.writeValueAsString(response)));
			}
			return null;
		});
	}

	private void enqueue(Input captured) {
		inTransaction(() -> {
			worker.enqueue(captured);
			return null;
		});
	}

	private Work work() {
		String encoded = jdbc.queryForObject("select request_state->>? from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, EncounterEvaluationWorker.WORKFLOW_KEY,
			input.matchingId(), input.leftUserId());
		return mapper.readValue(encoded, Work.class);
	}

	private <T> T inTransaction(Supplier<T> action) {
		return new TransactionTemplate(transactions).execute(status -> action.get());
	}

	private long user() {
		return jdbc.queryForObject("insert into users(provider,provider_user_id,status,created_at) "
			+ "values ('GOOGLE',?,'ACTIVE',?) returning id", Long.class, UUID.randomUUID().toString(),
			Timestamp.from(NOW));
	}

	private long keyring(long userId) {
		return jdbc.queryForObject("insert into keyring(serial_number,firmware_version,owner_user_id,state) "
			+ "values (?,'test',?,'REGISTERED') returning id", Long.class, UUID.randomUUID().toString(), userId);
	}

	private static String fingerprint(byte signature) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[] {signature}));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("test gateway wait expired");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
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
