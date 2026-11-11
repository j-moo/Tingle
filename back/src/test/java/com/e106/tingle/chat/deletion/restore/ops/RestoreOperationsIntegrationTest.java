package com.e106.tingle.chat.deletion.restore.ops;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Inventory;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Reference;
import com.e106.tingle.chat.deletion.ChatDeletionRepository;
import com.e106.tingle.chat.deletion.ChatDeletionState;
import com.e106.tingle.chat.deletion.evidence.FileBackupEvidence.Entry;
import com.e106.tingle.chat.deletion.evidence.FileBackupEvidence.Snapshot;
import com.e106.tingle.chat.deletion.external.ExternalCleanupRepository;
import com.e106.tingle.chat.deletion.external.ExternalCleanupState.Status;
import com.e106.tingle.chat.deletion.restore.FileDeletionJournal;
import com.e106.tingle.chat.deletion.restore.RestoreServingGateFilter;

import tools.jackson.databind.json.JsonMapper;

/** The command opens only this disposable DB; no Spring application or scheduled workers are started. */
@Testcontainers
class RestoreOperationsIntegrationTest {
	// 복구 명령은 DB 이름(tingle_dev)까지 확인하므로 공용 컨테이너(SharedPostgres) 대신 전용 컨테이너를 쓴다
	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
		.withDatabaseName("tingle_dev");
	@TempDir
	private Path temporary;
	private final JsonMapper mapper = JsonMapper.builder().build();
	private JdbcTemplate jdbc;
	private TransactionTemplate transaction;
	private ChatDeletionRepository deletions;

	@BeforeEach
	void createOnlyDisposableRepositoryTables() {
		DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(source);
		transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
		deletions = new ChatDeletionRepository(jdbc, mapper);
		jdbc.execute("drop schema public cascade");
		jdbc.execute("create schema public");
		jdbc.execute("create table matching(id bigint primary key,chat_deletion_state jsonb,"
			+ "chat_external_cleanup_state jsonb)");
		jdbc.execute("create table chat(id bigint primary key,matching_id bigint not null,"
			+ "created_at timestamptz,expires_at timestamptz,closed_at timestamptz,delete_at timestamptz,"
			+ "retain_until timestamptz,close_reason text,ai_state jsonb,icebreakers jsonb)");
		jdbc.execute("create table chat_participant(id bigint primary key,chat_id bigint,notice_state jsonb)");
		jdbc.execute("create table chat_safety_notice(id bigint primary key,chat_participant_id bigint,"
			+ "notice_type text,created_at timestamptz,displayed_at timestamptz)");
		jdbc.execute("create table chat_report(chat_id bigint,created_at timestamptz)");
		jdbc.execute("create table chat_message(id bigint primary key,chat_id bigint)");
	}

	@Test
	void journalInitializationAndStatusNeedNoDatabaseAndNeverResetAnExistingJournal() throws Exception {
		Path directory = Files.createDirectory(temporary.resolve("journal"));
		Map<String, String> env = Map.of("TINGLE_CHAT_DELETION_JOURNAL_DIRECTORY", directory.toString());
		assertThat(run(new String[] {"journal-init", "--namespace=tingle_dev"}, env).code()).isZero();
		FileDeletionJournal journal = new FileDeletionJournal(directory.toString(), mapper);
		String fingerprint = journal.snapshot().fingerprint();
		Result status = run(new String[] {"journal-status", "--namespace=tingle_dev"}, env);
		assertThat(status.code()).isZero();
		assertThat(status.output()).contains(fingerprint);
		assertThat(mapper.readTree(status.output()).isObject()).isTrue();
		assertThat(run(new String[] {"journal-init", "--namespace=tingle_dev"}, env).code()).isNotZero();
		assertThat(journal.snapshot().fingerprint()).isEqualTo(fingerprint);
		assertThat(journal.snapshot().entries()).isEmpty();
	}

	@Test
	void wrongActualDatabaseNamespaceCannotChangeRowsJournalOrMarker() throws Exception {
		Map<String, String> env = environment("tingle");
		insertChat();
		FileDeletionJournal journal = journal(env);
		String fingerprint = journal.snapshot().fingerprint();
		Path marker = Path.of(env.get("TINGLE_CHAT_RESTORE_MARKER_FILE"));
		Files.writeString(marker, "unchanged-marker");
		Result result = run(verifyArguments(env, fingerprint, 0), env);
		assertThat(result.code()).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from chat", Long.class)).isOne();
		assertThat(jdbc.queryForObject("select chat_deletion_state::text from matching where id=1", String.class))
			.isNull();
		assertThat(journal.snapshot().fingerprint()).isEqualTo(fingerprint);
		assertThat(Files.readString(marker)).isEqualTo("unchanged-marker");
		assertThat(Files.exists(Path.of(marker + ".pending"))).isFalse();
		assertThat(result.output()).doesNotContain(POSTGRES.getJdbcUrl(), POSTGRES.getPassword(), "SQLException");
	}

	@Test
	void missingOrMismatchedRestoreGenerationNeverStartsDatabaseReplay() throws Exception {
		Map<String, String> env = environment("tingle_dev");
		insertChat();
		String fingerprint = journal(env).snapshot().fingerprint();
		String[] args = verifyArguments(env, fingerprint, 0);
		Path marker = Path.of(env.get("TINGLE_CHAT_RESTORE_MARKER_FILE"));
		Files.writeString(marker, "unchanged-marker");
		for (String generation : List.of("", UUID.randomUUID().toString())) {
			Map<String, String> invalid = new HashMap<>(env);
			if (generation.isEmpty()) {
				invalid.remove("TINGLE_CHAT_RESTORE_GENERATION");
			} else {
				invalid.put("TINGLE_CHAT_RESTORE_GENERATION", generation);
			}
			assertThat(run(args, invalid).code()).isEqualTo(2);
			assertThat(Files.readString(marker)).isEqualTo("unchanged-marker");
			assertThat(jdbc.queryForObject("select count(*) from chat", Long.class)).isOne();
		}
		Files.writeString(Path.of(env.get("APP_BACKUP_EVIDENCE_GENERATION_FILE")), UUID.randomUUID().toString());
		assertThat(run(args, env).code()).isEqualTo(2);
		assertThat(Files.readString(marker)).isEqualTo("unchanged-marker");
		assertThat(jdbc.queryForObject("select count(*) from chat", Long.class)).isOne();
	}

	@Test
	@Timeout(25)
	void actualRestoreWaitsForAFreshScanAndOpensTheGateWithoutReplacingLegacyApproval() throws Exception {
		Map<String, String> env = environment("tingle_dev");
		FileDeletionJournal journal = journal(env);
		Inventory original = prepareRestoredLegacyChat(env);
		String fingerprint = journal.snapshot().fingerprint();
		writeEvidence(env, Instant.now().minusSeconds(1));
		Path marker = Path.of(env.get("TINGLE_CHAT_RESTORE_MARKER_FILE"));
		RestoreServingGateFilter gate = new RestoreServingGateFilter(env.get("TINGLE_CHAT_RESTORE_GENERATION"),
			marker.toString(), mapper, journal);
		var executor = Executors.newSingleThreadExecutor();
		try {
			var result = executor.submit(() -> run(verifyArguments(env, fingerprint, 8), env));
			Instant deadline = Instant.now().plusSeconds(7);
			boolean pending = false;
			while (Instant.now().isBefore(deadline) && !result.isDone()) {
				String failure = jdbc.queryForObject("select chat_external_cleanup_state"
					+ "#>>'{targets,0,failureCode}' from matching where id=1", String.class);
				if ("BACKUP_SCAN_TOO_OLD".equals(failure)) {
					pending = true;
					break;
				}
				TimeUnit.MILLISECONDS.sleep(40);
			}
			String earlyResult = result.isDone() ? result.get().toString() : "command still running";
			assertThat(pending).as("scan before restore epoch must be rejected; %s", earlyResult).isTrue();
			assertThat(gate.servingAllowed()).isFalse();
			assertThat(Files.exists(Path.of(marker + ".pending"))).isTrue();
			writeEvidence(env, Instant.now());
			Result completed = result.get(15, TimeUnit.SECONDS);
			assertThat(completed.code()).as(completed.output()).isZero();
			assertThat(mapper.readTree(completed.output()).isObject()).isTrue();
			assertThat(gate.servingAllowed()).isTrue();
			assertThat(Files.exists(Path.of(marker + ".pending"))).isFalse();
			assertThat(jdbc.queryForObject("select count(*) from chat", Long.class)).isZero();
			assertThat(deletions.state(1).orElseThrow().inventory()).isEqualTo(original);
			var cleanup = transaction.execute(status -> new ExternalCleanupRepository(jdbc, mapper)
				.lock(1).orElseThrow().cleanup());
			assertThat(cleanup.status()).isEqualTo(Status.VERIFIED);
			assertThat(cleanup.approval().inventory()).isEqualTo(original);
			assertThat(cleanup.targets().getFirst().reference()).isEqualTo(original.references().getFirst());
			assertThat(cleanup.dbRestoreGeneration())
				.isEqualTo(UUID.fromString(env.get("TINGLE_CHAT_RESTORE_GENERATION")));
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@Timeout(30)
	void failedOrInterruptedVerificationCanResumeWithTheSameUuidAndFreshEvidence(boolean interrupted)
		throws Exception {
		Map<String, String> env = environment("tingle_dev");
		Inventory original = prepareRestoredLegacyChat(env);
		FileDeletionJournal journal = journal(env);
		String fingerprint = journal.snapshot().fingerprint();
		String generation = env.get("TINGLE_CHAT_RESTORE_GENERATION");
		Path marker = Path.of(env.get("TINGLE_CHAT_RESTORE_MARKER_FILE"));
		RestoreServingGateFilter gate = new RestoreServingGateFilter(generation, marker.toString(), mapper, journal);
		writeEvidence(env, Instant.now().minusSeconds(1));
		Result stopped;
		if (interrupted) {
			CompletableFuture<Result> completion = new CompletableFuture<>();
			AtomicBoolean interruptedOnExit = new AtomicBoolean();
			Thread worker = new Thread(() -> {
				try {
					Result outcome = run(verifyArguments(env, fingerprint, 8), env);
					interruptedOnExit.set(Thread.currentThread().isInterrupted());
					completion.complete(outcome);
				} catch (Exception exception) {
					completion.completeExceptionally(exception);
				}
			}, "synthetic-restore-interruption");
			worker.start();
			try {
				awaitPendingScan(completion, 0);
				worker.interrupt();
				stopped = completion.get(5, TimeUnit.SECONDS);
			} finally {
				worker.interrupt();
				worker.join(5000);
				assertThat(worker.isAlive()).isFalse();
			}
			assertThat(interruptedOnExit).isTrue();
		} else {
			stopped = run(verifyArguments(env, fingerprint, 0), env);
		}
		assertThat(stopped.code()).isEqualTo(3);
		assertThat(gate.servingAllowed()).isFalse();
		assertThat(Files.exists(Path.of(marker + ".pending"))).isTrue();
		ChatDeletionState firstReplay = deletions.state(1).orElseThrow();
		int previousAttempt = jdbc.queryForObject("select (chat_external_cleanup_state->>'attempt')::int"
			+ " from matching where id=1", Integer.class);
		var executor = Executors.newSingleThreadExecutor();
		try {
			var retry = executor.submit(() -> run(verifyArguments(env, fingerprint, 8), env));
			awaitPendingScan(retry, previousAttempt);
			writeEvidence(env, Instant.now());
			Result completed = retry.get(12, TimeUnit.SECONDS);
			assertThat(completed.code()).as(completed.output()).isZero();
			assertThat(gate.servingAllowed()).isTrue();
			assertThat(Files.exists(Path.of(marker + ".pending"))).isFalse();
			ChatDeletionState resumed = deletions.state(1).orElseThrow();
			assertThat(resumed.attempt()).isEqualTo(firstReplay.attempt());
			assertThat(resumed.inventory()).isEqualTo(original);
			assertThat(env.get("TINGLE_CHAT_RESTORE_GENERATION")).isEqualTo(generation);
			assertThat(journal.snapshot().fingerprint()).isEqualTo(fingerprint);
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@ParameterizedTest
	@EnumSource(UnconfirmedEvidence.class)
	void previousGenerationStaleEvidenceAndPresentBackupsKeepTheGateClosed(UnconfirmedEvidence failure)
		throws Exception {
		Map<String, String> env = environment("tingle_dev");
		Inventory original = prepareRestoredLegacyChat(env);
		FileDeletionJournal journal = journal(env);
		Instant inspected = failure == UnconfirmedEvidence.STALE ? Instant.now().minusSeconds(301) : Instant.now();
		String generation = failure == UnconfirmedEvidence.PREVIOUS_GENERATION ? UUID.randomUUID().toString()
			: env.get("TINGLE_CHAT_RESTORE_GENERATION");
		List<Entry> entries = failure == UnconfirmedEvidence.PRESENT
			? List.of(new Entry("a".repeat(64), true, Instant.now().minusSeconds(1))) : List.of();
		writeEvidence(env, new Snapshot(1, "tingle_dev", env.get("APP_BACKUP_EVIDENCE_SCOPE_ID"),
			generation, inspected, true, entries));
		Result result = run(verifyArguments(env, journal.snapshot().fingerprint(), 0), env);
		assertThat(result.code()).isEqualTo(3);
		Path marker = Path.of(env.get("TINGLE_CHAT_RESTORE_MARKER_FILE"));
		assertThat(new RestoreServingGateFilter(env.get("TINGLE_CHAT_RESTORE_GENERATION"), marker.toString(),
			mapper, journal).servingAllowed()).isFalse();
		assertThat(Files.exists(Path.of(marker + ".pending"))).isTrue();
		assertThat(deletions.state(1).orElseThrow().inventory()).isEqualTo(original);
		String expected = failure == UnconfirmedEvidence.PRESENT
			? "BACKUP_STILL_PRESENT" : "BACKUP_EVIDENCE_UNAVAILABLE";
		assertThat(jdbc.queryForObject("select chat_external_cleanup_state#>>'{targets,0,failureCode}'"
			+ " from matching where id=1", String.class)).isEqualTo(expected);
	}

	private void awaitPendingScan(Future<Result> result, int previousAttempt) throws Exception {
		Instant deadline = Instant.now().plusSeconds(6);
		boolean pending = false;
		while (Instant.now().isBefore(deadline) && !result.isDone()) {
			pending = Boolean.TRUE.equals(jdbc.queryForObject("select coalesce("
				+ "(chat_external_cleanup_state->>'attempt')::int>? and "
				+ "chat_external_cleanup_state#>>'{targets,0,failureCode}'='BACKUP_SCAN_TOO_OLD',false)"
				+ " from matching where id=1", Boolean.class, previousAttempt));
			if (pending) {
				break;
			}
			TimeUnit.MILLISECONDS.sleep(40);
		}
		String earlyResult = result.isDone() ? result.get().toString() : "command still running";
		assertThat(pending).as("new command must require its own fresh scan; %s", earlyResult).isTrue();
	}

	private Inventory prepareRestoredLegacyChat(Map<String, String> env) throws Exception {
		FileDeletionJournal journal = journal(env);
		Inventory original = new Inventory(true, false, env.get("APP_BACKUP_EVIDENCE_APPROVAL_ID"),
			List.of(new Reference("local-db-backup", "file-v1",
				env.get("APP_BACKUP_EVIDENCE_SCOPE_ID") + ":tingle_dev:" + UUID.randomUUID())));
		insertChat();
		String restoredChat = jdbc.queryForObject("select to_jsonb(c)::text from chat c where id=2", String.class);
		var context = transaction.execute(status -> deletions.lockContext(1, Instant.now(), null).orElseThrow());
		UUID deletionGeneration = UUID.randomUUID();
		journal.record(new ChatDeletionState(1, deletionGeneration, context, ChatDeletionState.Status.RUNNING,
			1, null, null, original, null, null));
		ChatDeletionState committed = new ChatDeletionState(1, deletionGeneration, context,
			ChatDeletionState.Status.DB_DELETED, 1, null, null, original, null, Instant.now().minusSeconds(10));
		transaction.executeWithoutResult(status -> {
			deletions.deleteChat(context);
			deletions.save(1, committed);
		});
		journal.record(committed);
		jdbc.update("insert into chat select * from jsonb_populate_record(null::chat,cast(? as jsonb))", restoredChat);
		return original;
	}

	private Map<String, String> environment(String namespace) throws Exception {
		Path directory = Files.createDirectory(temporary.resolve("journal"));
		Map<String, String> env = new HashMap<>();
		env.put("TINGLE_CHAT_DELETION_JOURNAL_DIRECTORY", directory.toString());
		assertThat(run(new String[] {"journal-init", "--namespace=" + namespace}, env).code()).isZero();
		env.put("TINGLE_CHAT_RESTORE_GENERATION", UUID.randomUUID().toString());
		Path restore = Files.createDirectory(temporary.resolve("restore"));
		env.put("TINGLE_CHAT_RESTORE_MARKER_FILE", restore.resolve("serving.marker").toString());
		env.put("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
		env.put("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
		env.put("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
		env.put("APP_BACKUP_EVIDENCE_ENABLED", "true");
		env.put("APP_BACKUP_EVIDENCE_FILE", temporary.resolve("inventory.json").toString());
		env.put("APP_BACKUP_EVIDENCE_GENERATION_FILE", temporary.resolve("restore-generation").toString());
		env.put("APP_BACKUP_EVIDENCE_NAMESPACE", namespace);
		env.put("APP_BACKUP_EVIDENCE_SCOPE_ID", "synthetic-db-scope");
		env.put("APP_BACKUP_EVIDENCE_APPROVAL_ID", "synthetic-restore-approval");
		Files.writeString(Path.of(env.get("APP_BACKUP_EVIDENCE_GENERATION_FILE")),
			env.get("TINGLE_CHAT_RESTORE_GENERATION"));
		writeEvidence(env, Instant.now());
		return env;
	}

	private void insertChat() {
		Instant closed = Instant.now().minus(Duration.ofDays(31));
		jdbc.update("insert into matching(id) values(1)");
		jdbc.update("insert into chat(id,matching_id,created_at,expires_at,closed_at,ai_state,icebreakers)"
			+ " values(2,1,?,?,?,'{}','{}')", Timestamp.from(closed.minus(Duration.ofDays(7))),
			Timestamp.from(closed), Timestamp.from(closed));
	}

	private FileDeletionJournal journal(Map<String, String> env) {
		return new FileDeletionJournal(env.get("TINGLE_CHAT_DELETION_JOURNAL_DIRECTORY"), mapper);
	}

	private void writeEvidence(Map<String, String> env, Instant inspected) throws Exception {
		writeEvidence(env, new Snapshot(1, env.get("APP_BACKUP_EVIDENCE_NAMESPACE"),
			env.get("APP_BACKUP_EVIDENCE_SCOPE_ID"), env.get("TINGLE_CHAT_RESTORE_GENERATION"), inspected,
			true, List.of()));
	}

	private void writeEvidence(Map<String, String> env, Snapshot snapshot) throws Exception {
		Path target = Path.of(env.get("APP_BACKUP_EVIDENCE_FILE"));
		Path staged = Files.createTempFile(temporary, "inventory-", ".json");
		Files.writeString(staged, mapper.writeValueAsString(snapshot));
		Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	private String[] verifyArguments(Map<String, String> env, String fingerprint, int wait) {
		return new String[] {"verify", "--namespace=" + env.get("APP_BACKUP_EVIDENCE_NAMESPACE"),
			"--restore-generation=" + env.get("TINGLE_CHAT_RESTORE_GENERATION"),
			"--expected-fingerprint=" + fingerprint, "--fence-confirmed=true", "--wait-seconds=" + wait,
			"--poll-seconds=1"};
	}

	private Result run(String[] args, Map<String, String> env) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
			int code = RestoreOperationsCommand.execute(args, env, output);
			return new Result(code, bytes.toString(StandardCharsets.UTF_8));
		}
	}

	private record Result(int code, String output) {
	}

	private enum UnconfirmedEvidence { PREVIOUS_GENERATION, STALE, PRESENT }
}
