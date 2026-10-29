package com.e106.tingle.chat.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Inventory;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Reference;
import com.e106.tingle.chat.deletion.ChatDeletionService.Claim;
import com.e106.tingle.chat.deletion.ChatDeletionService.Outcome;
import com.e106.tingle.chat.deletion.ChatDeletionState.Status;

/** Only the new disposable PostgreSQL container supplied by TestcontainersConfiguration is used. */
@SpringBootTest
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class ChatDeletionIntegrationTest {
	private static final Instant NOW = Instant.parse("2026-10-29T03:00:00Z");
	private static final Duration NORMAL_RETENTION = Duration.ofHours(720);
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private ChatDeletionService service;
	@Autowired
	private ApplicationContext application;
	@Autowired
	private PlatformTransactionManager transactions;
	@MockitoSpyBean
	private ChatDeletionRepository repository;
	@MockitoBean
	private Clock clock;
	@MockitoBean
	private ChatDeletionReferenceSource references;
	private final List<Fixture> fixtures = new ArrayList<>();

	@BeforeEach
	void prepareVerifiedTestMetadata() {
		given(clock.instant()).willReturn(NOW);
		given(references.inspect(any())).willAnswer(invocation -> {
			var context = (ChatDeletionReferenceSource.Context)invocation.getArgument(0);
			return new Inventory(true, false, "test-contract-v1",
				List.of(new Reference("test-provider", "v1", "copy-" + context.chatId())));
		});
	}

	@AfterEach
	void removeOnlyThisTestsDisposableFixtures() {
		for (Fixture fixture : fixtures) {
			jdbc.update("delete from chat_report_message where report_id in "
				+ "(select id from chat_report where chat_id=?)", fixture.chat());
			jdbc.update("delete from chat_report where chat_id=?", fixture.chat());
			jdbc.update("delete from chat_safety_notice where chat_participant_id in "
				+ "(select id from chat_participant where chat_id=?)", fixture.chat());
			jdbc.update("delete from chat_message where chat_id=?", fixture.chat());
			jdbc.update("delete from chat_participant where chat_id=?", fixture.chat());
			jdbc.update("delete from chat where id=?", fixture.chat());
			jdbc.update("delete from matching_participant where matching_id=?", fixture.matching());
			jdbc.update("delete from matching where id=?", fixture.matching());
			for (long userId : List.of(fixture.firstUser(), fixture.secondUser())) {
				jdbc.update("delete from profile where user_id=?", userId);
				jdbc.update("delete from user_consents where user_id=?", userId);
				jdbc.update("delete from users where id=?", userId);
			}
		}
	}

	@Test
	void schedulerIsOffByDefaultAndTheThresholdDoesNotMove() {
		Fixture fixture = fixture(NOW.minus(NORMAL_RETENTION).plusSeconds(1), false);
		assertThat(application.getBeansOfType(ChatDeletionScheduler.class)).isEmpty();
		assertThat(service.claimNext()).isEmpty();
		assertThat(count("chat", "id", fixture.chat())).isEqualTo(1);
		given(clock.instant()).willReturn(NOW.plusSeconds(1));
		assertThat(service.deleteClaim(service.claimNext().orElseThrow())).isEqualTo(Outcome.DELETED);
		assertThat(service.handoff(fixture.matching()).orElseThrow().context().closedAt())
			.isEqualTo(NOW.minus(NORMAL_RETENTION).plusSeconds(1));
	}

	@Test
	void naturalExpiryUsesTheOriginalExpiryAndPreservesReferencesBeforeDeletingOnlyChatRows() {
		Fixture fixture = fixture(NOW.minus(NORMAL_RETENTION), true);
		Claim claim = service.claimNext().orElseThrow();
		assertThat(jdbc.queryForObject("select closed_at from chat where id=?", Timestamp.class, fixture.chat()))
			.isEqualTo(Timestamp.from(NOW.minus(NORMAL_RETENTION)));
		assertThat(repository.state(fixture.matching()).orElseThrow().inventory().references()).hasSize(1);
		assertThat(service.deleteClaim(claim)).isEqualTo(Outcome.DELETED);
		assertThat(service.deleteClaim(claim)).isEqualTo(Outcome.ALREADY_DELETED);
		assertThat(count("chat", "id", fixture.chat())).isZero();
		assertThat(count("chat_message", "chat_id", fixture.chat())).isZero();
		assertThat(count("chat_participant", "chat_id", fixture.chat())).isZero();
		assertThat(count("matching", "id", fixture.matching())).isEqualTo(1);
		assertThat(count("matching_participant", "matching_id", fixture.matching())).isEqualTo(2);
		assertThat(count("users", "id", fixture.firstUser())).isEqualTo(1);
		assertThat(count("profile", "user_id", fixture.firstUser())).isEqualTo(1);
		assertThat(count("user_consents", "user_id", fixture.firstUser())).isEqualTo(1);
		ChatDeletionState handoff = service.handoff(fixture.matching()).orElseThrow();
		assertThat(handoff.inventory().references().getFirst().opaqueId()).isEqualTo("copy-" + fixture.chat());
		assertThat(handoff.inventory().approvalId()).isEqualTo("test-contract-v1");
		assertThatThrownBy(() -> jdbc.update("insert into matching(user_low_id,user_high_id,status) "
			+ "values (?,?,'PENDING')", fixture.firstUser(), fixture.secondUser()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void computesNinetyDaysFromTheFirstReportWithoutDeletingReportOrEvidenceRows() {
		Fixture fixture = fixture(NOW.minus(NORMAL_RETENTION), false);
		report(fixture, NOW.minus(Duration.ofDays(89)));
		report(fixture, NOW.minus(Duration.ofDays(1)));
		assertThat(service.claimNext()).isEmpty();
		assertThat(repository.state(fixture.matching()).orElseThrow().context().dueAt())
			.isEqualTo(NOW.plus(Duration.ofDays(1)));
		given(clock.instant()).willReturn(NOW.plus(Duration.ofDays(1)));
		assertThat(service.deleteClaim(service.claimNext().orElseThrow())).isEqualTo(Outcome.DELETED);
		assertThat(count("chat_report", "chat_id", fixture.chat())).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from chat_report_message where message_id=?",
			Long.class, fixture.message())).isEqualTo(2);
	}

	@Test
	void neverShortensAStoredRetainUntilEvenWithoutAReportOrAfterItIsCleared() {
		Fixture fixture = fixture(NOW.minus(NORMAL_RETENTION), false);
		Instant retained = NOW.plus(Duration.ofDays(10));
		jdbc.update("update chat set retain_until=? where id=?", Timestamp.from(retained), fixture.chat());
		assertThat(service.claimNext()).isEmpty();
		jdbc.update("update chat set retain_until=null where id=?", fixture.chat());
		given(clock.instant()).willReturn(NOW.plus(Duration.ofDays(1)));
		assertThat(service.claimNext()).isEmpty();
		assertThat(repository.state(fixture.matching()).orElseThrow().context().dueAt()).isEqualTo(retained);
		assertThat(count("chat", "id", fixture.chat())).isEqualTo(1);
	}

	@Test
	void blocksMissingInventoryAndDoesNotTurnAnUnattestedEmptyListIntoSuccess() {
		Fixture fixture = fixture(NOW.minus(NORMAL_RETENTION), false);
		doReturn(Inventory.unresolved()).when(references).inspect(any());
		assertThat(service.claimNext()).isEmpty();
		assertThat(repository.state(fixture.matching()).orElseThrow().status()).isEqualTo(Status.BLOCKED_REFERENCES);
		given(clock.instant()).willReturn(NOW.plusSeconds(300));
		doReturn(new Inventory(true, false, "test-contract-v1", List.of())).when(references).inspect(any());
		assertThat(service.claimNext()).isEmpty();
		assertThat(count("chat", "id", fixture.chat())).isEqualTo(1);
		assertThat(service.handoff(fixture.matching())).isEmpty();
	}

	@Test
	void acceptsAnExplicitApprovedAttestationThatThereAreNoExternalCopies() {
		Fixture fixture = fixture(NOW.minus(NORMAL_RETENTION), false);
		doReturn(new Inventory(true, true, "test-no-copies-v1", List.of())).when(references).inspect(any());
		assertThat(service.deleteClaim(service.claimNext().orElseThrow())).isEqualTo(Outcome.DELETED);
		assertThat(service.handoff(fixture.matching()).orElseThrow().inventory().absenceConfirmed()).isTrue();
	}

	@Test
	void leaseReclaimFencesOffOldAttemptsAndPreservesTheApprovedInventory() {
		Fixture fixture = fixture(NOW.minus(NORMAL_RETENTION), false);
		Claim old = service.claimNext().orElseThrow();
		given(clock.instant()).willReturn(NOW.plusSeconds(60));
		doReturn(Inventory.unresolved()).when(references).inspect(any());
		Claim replacement = service.claimNext().orElseThrow();
		assertThat(replacement.generation()).isEqualTo(old.generation());
		assertThat(replacement.attempt()).isEqualTo(old.attempt() + 1);
		assertThat(service.deleteClaim(old)).isEqualTo(Outcome.STALE);
		assertThat(service.failClaim(old, "OLD_FAILURE")).isFalse();
		assertThat(service.deleteClaim(replacement)).isEqualTo(Outcome.DELETED);
		assertThat(service.handoff(fixture.matching()).orElseThrow().inventory().references()).hasSize(1);
	}

	@Test
	void rechecksReportsArrivingAfterTheClaimBeforeAnyPhysicalDeletion() {
		Fixture fixture = fixture(NOW.minus(NORMAL_RETENTION), false);
		Claim claim = service.claimNext().orElseThrow();
		report(fixture, NOW);
		assertThat(service.deleteClaim(claim)).isEqualTo(Outcome.RETAINED);
		assertThat(count("chat_message", "chat_id", fixture.chat())).isEqualTo(1);
		assertThat(repository.state(fixture.matching()).orElseThrow().context().dueAt())
			.isEqualTo(NOW.plus(Duration.ofDays(90)));
	}

	@Test
	void changedSourceMetadataCannotDiscardCapturedExternalReferences() {
		Fixture fixture = fixture(NOW.minus(NORMAL_RETENTION), false);
		Claim claim = service.claimNext().orElseThrow();
		jdbc.update("update chat set ai_state='{\"testGeneration\":2}' where id=?", fixture.chat());
		assertThat(service.deleteClaim(claim)).isEqualTo(Outcome.BLOCKED);
		ChatDeletionState state = repository.state(fixture.matching()).orElseThrow();
		assertThat(state.status()).isEqualTo(Status.BLOCKED_SOURCE_CHANGED);
		assertThat(state.inventory().references()).hasSize(1);
		assertThat(count("chat", "id", fixture.chat())).isEqualTo(1);
	}

	@Test
	void changedChatGenerationIsNotDeletedByTheOldClaim() {
		Fixture fixture = fixture(NOW.minus(NORMAL_RETENTION), false);
		Claim claim = service.claimNext().orElseThrow();
		jdbc.update("update chat set closed_at=closed_at+interval '1 second' where id=?", fixture.chat());
		assertThat(service.deleteClaim(claim)).isEqualTo(Outcome.BLOCKED);
		assertThat(repository.state(fixture.matching()).orElseThrow().status()).isEqualTo(Status.BLOCKED_GENERATION);
		assertThat(count("chat_message", "chat_id", fixture.chat())).isEqualTo(1);
	}

	@Test
	void childDeletionFailureRollsBackAllRowsAndCanRetryWithoutLosingTheHandoff() {
		Fixture fixture = fixture(NOW.minus(NORMAL_RETENTION), false);
		safetyNotice(fixture);
		Claim claim = service.claimNext().orElseThrow();
		doAnswer(invocation -> {
			jdbc.update("delete from chat_safety_notice where chat_participant_id=?", fixture.sender());
			jdbc.update("delete from chat_message where chat_id=?", fixture.chat());
			throw new IllegalStateException("test-only failure after child deletion");
		}).when(repository).deleteChat(any());
		assertThatThrownBy(() -> service.deleteClaim(claim)).isInstanceOf(RuntimeException.class)
			.hasMessageContaining("test-only failure");
		assertThat(count("chat_message", "chat_id", fixture.chat())).isEqualTo(1);
		assertThat(count("chat_safety_notice", "chat_participant_id", fixture.sender())).isEqualTo(1);
		assertThat(count("chat_participant", "chat_id", fixture.chat())).isEqualTo(2);
		assertThat(service.failClaim(claim, "TEST_DELETE_FAILED")).isTrue();
		doCallRealMethod().when(repository).deleteChat(any());
		given(clock.instant()).willReturn(NOW.plusSeconds(60));
		doReturn(Inventory.unresolved()).when(references).inspect(any());
		assertThat(service.deleteClaim(service.claimNext().orElseThrow())).isEqualTo(Outcome.DELETED);
		assertThat(service.handoff(fixture.matching()).orElseThrow().inventory().references()).hasSize(1);
	}

	@Test
	void deletesOnlyTheTargetRoomsSafetyNoticesAndKeepsReportEvidence() {
		Fixture target = fixture(NOW.minus(NORMAL_RETENTION), false);
		Fixture other = fixture(NOW.minus(Duration.ofHours(700)), false);
		safetyNotice(target);
		safetyNotice(other);
		report(target, NOW.minus(Duration.ofDays(90)));
		assertThat(service.deleteClaim(service.claimNext().orElseThrow())).isEqualTo(Outcome.DELETED);
		assertThat(count("chat_safety_notice", "chat_participant_id", target.sender())).isZero();
		assertThat(count("chat_safety_notice", "chat_participant_id", other.sender())).isEqualTo(1);
		assertThat(count("chat_participant", "chat_id", other.chat())).isEqualTo(2);
		assertThat(count("chat_report", "chat_id", target.chat())).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from chat_report_message where message_id=?",
			Long.class, target.message())).isEqualTo(1);
		assertThat(count("users", "id", target.firstUser())).isEqualTo(1);
	}

	@Test
	void newSafetyNoticeAfterInventoryCaptureBlocksDeletionWithoutLosingReferences() {
		Fixture fixture = fixture(NOW.minus(NORMAL_RETENTION), false);
		Claim claim = service.claimNext().orElseThrow();
		safetyNotice(fixture);
		assertThat(service.deleteClaim(claim)).isEqualTo(Outcome.BLOCKED);
		assertThat(count("chat_safety_notice", "chat_participant_id", fixture.sender())).isEqualTo(1);
		assertThat(count("chat", "id", fixture.chat())).isEqualTo(1);
		assertThat(repository.state(fixture.matching()).orElseThrow().status())
			.isEqualTo(Status.BLOCKED_SOURCE_CHANGED);
		assertThat(repository.state(fixture.matching()).orElseThrow().inventory().references()).hasSize(1);
	}

	@Test
	void concurrentClaimsOwnDistinctRooms() throws Exception {
		fixture(NOW.minus(NORMAL_RETENTION), false);
		fixture(NOW.minus(NORMAL_RETENTION), false);
		List<Claim> claims = concurrently(() -> service.claimNext().orElseThrow(),
			() -> service.claimNext().orElseThrow());
		assertThat(claims.getFirst().matchingId()).isNotEqualTo(claims.getLast().matchingId());
		assertThat(service.deleteClaim(claims.getFirst())).isEqualTo(Outcome.DELETED);
		assertThat(service.deleteClaim(claims.getLast())).isEqualTo(Outcome.DELETED);
	}

	@Test
	void retryCannotReplaceTheFirstApprovedReferencesWithAnotherApprovedInventory() {
		Fixture fixture = fixture(NOW.minus(NORMAL_RETENTION), false);
		Claim first = service.claimNext().orElseThrow();
		assertThat(service.failClaim(first, "TEST_RETRY")).isTrue();
		doReturn(new Inventory(true, false, "different-approval",
			List.of(new Reference("another-provider", "v2", "replacement-copy"))))
			.when(references).inspect(any());
		given(clock.instant()).willReturn(NOW.plusSeconds(60));
		assertThat(service.deleteClaim(service.claimNext().orElseThrow())).isEqualTo(Outcome.DELETED);
		assertThat(service.handoff(fixture.matching()).orElseThrow().inventory().approvalId())
			.isEqualTo("test-contract-v1");
		assertThat(service.handoff(fixture.matching()).orElseThrow().inventory().references())
			.containsExactly(new Reference("test-provider", "v1", "copy-" + fixture.chat()));
	}

	@Test
	void restoredSameGenerationIsDeletedAgainWithoutLosingTheFirstHandoff() {
		Fixture fixture = fixture(NOW.minus(NORMAL_RETENTION), false);
		Backup backup = backup(fixture);
		Claim first = service.claimNext().orElseThrow();
		assertThat(service.deleteClaim(first)).isEqualTo(Outcome.DELETED);
		restore(backup);
		assertThat(service.deleteClaim(first)).isEqualTo(Outcome.STALE);
		given(clock.instant()).willReturn(NOW.plusSeconds(120));
		Claim restored = service.claimNext().orElseThrow();
		assertThat(restored.generation()).isEqualTo(first.generation());
		assertThat(restored.attempt()).isGreaterThan(first.attempt());
		assertThat(service.handoff(fixture.matching()).orElseThrow().dbDeletedAt()).isEqualTo(NOW);
		assertThat(service.deleteClaim(restored)).isEqualTo(Outcome.DELETED);
		assertThat(count("chat", "id", fixture.chat())).isZero();
		assertThat(service.handoff(fixture.matching()).orElseThrow().dbDeletedAt()).isEqualTo(NOW);
	}

	@Test
	void restoredDifferentGenerationIsBlockedAndKeepsTheOriginalHandoff() {
		Fixture fixture = fixture(NOW.minus(NORMAL_RETENTION), false);
		Backup backup = backup(fixture);
		assertThat(service.deleteClaim(service.claimNext().orElseThrow())).isEqualTo(Outcome.DELETED);
		restore(backup);
		jdbc.update("update chat set created_at=created_at+interval '1 second' where id=?", fixture.chat());
		assertThat(service.claimNext()).isEmpty();
		ChatDeletionState state = service.handoff(fixture.matching()).orElseThrow();
		assertThat(state.status()).isEqualTo(Status.BLOCKED_GENERATION);
		assertThat(state.inventory().references()).hasSize(1);
		assertThat(count("chat", "id", fixture.chat())).isEqualTo(1);
	}

	@Test
	void concurrentReportWriterAndDeletionUseMatchingThenChatLocksWithoutAGlobalTableLock() throws Exception {
		Fixture fixture = fixture(NOW.minus(NORMAL_RETENTION), false);
		Claim claim = service.claimNext().orElseThrow();
		CountDownLatch reportLocked = new CountDownLatch(1);
		CountDownLatch releaseReport = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> reportWrite = executor.submit(() -> new TransactionTemplate(transactions)
				.executeWithoutResult(tx -> {
					jdbc.queryForObject("select id from matching where id=? for update", Long.class,
						fixture.matching());
					jdbc.queryForObject("select id from chat where id=? for update", Long.class, fixture.chat());
					report(fixture, NOW);
					reportLocked.countDown();
					await(releaseReport);
				}));
			assertThat(reportLocked.await(10, TimeUnit.SECONDS)).isTrue();
			Future<Outcome> deletion = executor.submit(() -> service.deleteClaim(claim));
			releaseReport.countDown();
			reportWrite.get(10, TimeUnit.SECONDS);
			assertThat(deletion.get(10, TimeUnit.SECONDS)).isEqualTo(Outcome.RETAINED);
			assertThat(count("chat_message", "chat_id", fixture.chat())).isEqualTo(1);
		} finally {
			releaseReport.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void concurrentNoticeWorkerCannotChangeCapturedMetadataWhileDeletionWaitsForItsParticipantLock() throws Exception {
		Fixture fixture = fixture(NOW.minus(NORMAL_RETENTION), false);
		Claim claim = service.claimNext().orElseThrow();
		CountDownLatch noticeLocked = new CountDownLatch(1);
		CountDownLatch releaseNotice = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> noticeWrite = executor.submit(() -> new TransactionTemplate(transactions)
				.executeWithoutResult(tx -> {
					jdbc.queryForObject("select id from chat_participant where id=? for update", Long.class,
						fixture.sender());
					jdbc.update("update chat_participant set notice_state='{\"testMetadataVersion\":2}' where id=?",
						fixture.sender());
					noticeLocked.countDown();
					await(releaseNotice);
				}));
			assertThat(noticeLocked.await(10, TimeUnit.SECONDS)).isTrue();
			Future<Outcome> deletion = executor.submit(() -> service.deleteClaim(claim));
			releaseNotice.countDown();
			noticeWrite.get(10, TimeUnit.SECONDS);
			assertThat(deletion.get(10, TimeUnit.SECONDS)).isEqualTo(Outcome.BLOCKED);
			assertThat(count("chat_message", "chat_id", fixture.chat())).isEqualTo(1);
		} finally {
			releaseNotice.countDown();
			executor.shutdownNow();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Test lock wait timed out");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Test interrupted", exception);
		}
	}

	private Backup backup(Fixture fixture) {
		return new Backup(jdbc.queryForObject("select to_jsonb(c)::text from chat c where id=?", String.class,
			fixture.chat()), jdbc.queryForObject("select jsonb_agg(to_jsonb(p) order by id)::text "
			+ "from chat_participant p where chat_id=?", String.class, fixture.chat()),
			jdbc.queryForObject("select jsonb_agg(to_jsonb(m) order by id)::text "
				+ "from chat_message m where chat_id=?", String.class, fixture.chat()));
	}

	private void restore(Backup backup) {
		jdbc.update("insert into chat overriding system value "
			+ "select * from jsonb_populate_record(null::chat,cast(? as jsonb))", backup.chat());
		jdbc.update("insert into chat_participant overriding system value "
			+ "select * from jsonb_populate_recordset(null::chat_participant,cast(? as jsonb))", backup.participants());
		jdbc.update("insert into chat_message overriding system value "
			+ "select * from jsonb_populate_recordset(null::chat_message,cast(? as jsonb))", backup.messages());
	}

	private Fixture fixture(Instant closed, boolean naturalExpiry) {
		long first = user();
		long second = user();
		long matching = jdbc.queryForObject("insert into matching(user_low_id,user_high_id,status) "
			+ "values (?,?,'CLOSED') returning id", Long.class, first, second);
		jdbc.update("insert into matching_participant(matching_id,user_id) values (?,?),(?,?)",
			matching, first, matching, second);
		long chat = jdbc.queryForObject("insert into chat(matching_id,created_at,expires_at,closed_at,"
			+ "last_message_seq,icebreakers,ai_state) values (?,?,?,?,1,'{}','{}') returning id", Long.class,
			matching, Timestamp.from(closed.minus(Duration.ofHours(168))), Timestamp.from(closed),
			naturalExpiry ? null : Timestamp.from(closed));
		long sender = jdbc.queryForObject("insert into chat_participant(chat_id,user_id,last_read_message_seq,"
			+ "notice_state,read_state) values (?,?,0,'{}','{}') returning id", Long.class, chat, first);
		jdbc.update("insert into chat_participant(chat_id,user_id,last_read_message_seq,notice_state,read_state) "
			+ "values (?,?,0,'{}','{}')", chat, second);
		long message = jdbc.queryForObject("insert into chat_message(chat_id,seq,sender_participant_id,body,"
			+ "client_message_id,request_key,request_hash,created_at) values (?,1,?,'test-only',?,?,?,?) returning id",
			Long.class, chat, sender, UUID.randomUUID(), UUID.randomUUID(), new byte[32], Timestamp.from(closed));
		Fixture fixture = new Fixture(matching, chat, sender, message, first, second);
		fixtures.add(fixture);
		return fixture;
	}

	private long user() {
		long user = jdbc.queryForObject("insert into users(provider,provider_user_id,status,created_at) "
			+ "values ('GOOGLE',?,'ACTIVE',?) returning id", Long.class,
			UUID.randomUUID().toString(), Timestamp.from(NOW));
		jdbc.update("insert into profile(user_id) values (?)", user);
		jdbc.update("insert into user_consents(user_id,consent_type,document_version,agreed,recorded_at) "
			+ "values (?,'TEST_ONLY','v1',true,?)", user, Timestamp.from(NOW));
		return user;
	}

	private void report(Fixture fixture, Instant created) {
		long report = jdbc.queryForObject("insert into chat_report(chat_id,reporter_participant_id,reason,status,"
			+ "created_at) values (?,?,'ETC','RECEIVED',?) returning id", Long.class,
			fixture.chat(), fixture.sender(), Timestamp.from(created));
		jdbc.update("insert into chat_report_message(report_id,message_id) values (?,?)", report, fixture.message());
	}

	private void safetyNotice(Fixture fixture) {
		jdbc.update("insert into chat_safety_notice(chat_participant_id,notice_type,created_at) "
			+ "values (?,'MONEY_REQUEST',?)", fixture.sender(), Timestamp.from(NOW));
	}

	private long count(String table, String key, long id) {
		// Identifiers are fixed test constants, never external input.
		return jdbc.queryForObject("select count(*) from " + table + " where " + key + "=?", Long.class, id);
	}

	@SafeVarargs
	private static <T> List<T> concurrently(Callable<T>... calls) throws Exception {
		var executor = Executors.newFixedThreadPool(calls.length);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> call : calls) {
				futures.add(executor.submit(() -> {
					start.await();
					return call.call();
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			return results;
		} finally {
			executor.shutdownNow();
		}
	}

	private record Fixture(long matching, long chat, long sender, long message, long firstUser, long secondUser) {
	}

	private record Backup(String chat, String participants, String messages) {
	}
}
