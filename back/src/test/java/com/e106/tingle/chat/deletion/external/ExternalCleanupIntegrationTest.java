package com.e106.tingle.chat.deletion.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Inventory;
import com.e106.tingle.chat.deletion.ChatDeletionReferenceSource.Reference;
import com.e106.tingle.chat.deletion.ChatDeletionRepository;
import com.e106.tingle.chat.deletion.ChatDeletionService;
import com.e106.tingle.chat.deletion.ChatDeletionState;
import com.e106.tingle.chat.deletion.external.ExternalCleanupService.Claim;
import com.e106.tingle.chat.deletion.external.ExternalCleanupState.Status;
import com.e106.tingle.chat.deletion.external.ExternalCopyDeletionPort.Outcome;
import com.e106.tingle.chat.deletion.external.ExternalCopyDeletionPort.Request;
import com.e106.tingle.chat.deletion.external.ExternalCopyDeletionPort.Result;
import com.e106.tingle.chat.deletion.restore.DeletionJournal;

import tools.jackson.databind.json.JsonMapper;

/** Synthetic providers run only in this test configuration and the newly created disposable PostgreSQL 17 container. */
@SpringBootTest
@ActiveProfiles({"test", "erd"})
@Import({TestcontainersConfiguration.class, ExternalCleanupIntegrationTest.Providers.class})
class ExternalCleanupIntegrationTest {
	private static final Instant NOW = Instant.parse("2026-10-29T03:00:00Z");
	private static final Reference FIRST = new Reference("synthetic-a", "v1", "copy-a");
	private static final Reference SECOND = new Reference("synthetic-b", "v1", "copy-b");
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private ExternalCleanupService service;
	@Autowired
	private ExternalCleanupRepository repository;
	@Autowired
	private ChatDeletionService deletion;
	@Autowired
	private ChatDeletionRepository deletions;
	@Autowired
	private JsonMapper mapper;
	@Autowired
	private PlatformTransactionManager transactions;
	@Autowired
	@Qualifier("syntheticFirst")
	private FakeProvider first;
	@Autowired
	@Qualifier("syntheticSecond")
	private FakeProvider second;
	@MockitoBean
	private Clock clock;
	@MockitoBean
	private ChatDeletionReferenceSource references;
	@MockitoBean
	private DeletionJournal journal;
	private final List<Fixture> fixtures = new ArrayList<>();
	private final Map<Long, Inventory> approved = new ConcurrentHashMap<>();

	@BeforeEach
	void prepareOnlySyntheticProviders() {
		given(clock.instant()).willReturn(NOW);
		given(references.inspect(any())).willAnswer(invocation -> {
			ChatDeletionReferenceSource.Context context = invocation.getArgument(0);
			return approved.getOrDefault(context.matchingId(), Inventory.unresolved());
		});
		first.reset();
		second.reset();
	}

	@AfterEach
	void removeOnlyTheseDisposableTestRows() {
		for (Fixture fixture : fixtures) {
			jdbc.update("delete from chat where matching_id=?", fixture.matching());
			jdbc.update("delete from matching where id=?", fixture.matching());
			jdbc.update("delete from users where id in (?,?)", fixture.firstUser(), fixture.secondUser());
		}
	}

	@Test
	void blocksAnUnconfirmedDbHandoffAndUnattestedEmptyInventory() {
		Fixture fixture = fixture(Inventory.unresolved(), false);
		assertThat(service.claim(fixture.matching())).isEmpty();
		assertThat(service.inspect(fixture.matching()).orElseThrow().status()).isEqualTo(Status.BLOCKED);
		assertThat(first.requests).isEmpty();
		approved.put(fixture.matching(), new Inventory(true, false, "test-approval", List.of()));
		assertThat(deletion.claimNext()).isEmpty();
		assertThat(service.claim(fixture.matching())).isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from chat where id=?", Long.class, fixture.chat())).isOne();
	}

	@Test
	void blocksAnIncompleteInventoryEvenWhenAHistoricalDbDeletionTimeExists() {
		Fixture fixture = fixture(inventory(FIRST), true);
		changeDb(fixture, state -> copy(state, state.attempt(), Inventory.unresolved(), state.status()));
		assertThat(service.claim(fixture.matching())).isEmpty();
		assertThat(service.inspect(fixture.matching()).orElseThrow().failureCode()).isEqualTo("INVENTORY_UNCONFIRMED");
		assertThat(first.requests).isEmpty();
	}

	@Test
	void verifiesZeroTargetsOnlyWithAnExplicitCompleteAbsenceAttestation() {
		Fixture fixture = fixture(new Inventory(true, true, "test-absence-approval", List.of()), true);
		ExternalCleanupService withoutAdapters = new ExternalCleanupService(repository, List.of(), clock, transactions);
		assertThat(withoutAdapters.claim(fixture.matching())).isEmpty();
		ExternalCleanupState state = withoutAdapters.inspect(fixture.matching()).orElseThrow();
		assertThat(state.status()).isEqualTo(Status.VERIFIED);
		assertThat(state.targets()).isEmpty();
		assertThat(state.approval().inventory().absenceConfirmed()).isTrue();
		assertThat(state.verifiedAt()).isEqualTo(NOW);
		assertThat(first.requests).isEmpty();
		assertThat(second.requests).isEmpty();
	}

	@Test
	void missingAdaptersBlockKnownTargetsWithoutChangingTheOriginalApproval() {
		Fixture fixture = fixture(inventory(FIRST), true);
		String original = dbDocument(fixture);
		ExternalCleanupService withoutAdapters = new ExternalCleanupService(repository, List.of(), clock, transactions);
		assertThat(withoutAdapters.claim(fixture.matching())).isEmpty();
		ExternalCleanupState state = withoutAdapters.inspect(fixture.matching()).orElseThrow();
		assertThat(state.status()).isEqualTo(Status.BLOCKED);
		assertThat(state.targets().getFirst().outcome()).isEqualTo(Outcome.BLOCKED_UNSUPPORTED);
		assertThat(state.approval().inventory().references()).containsExactly(FIRST);
		assertThat(dbDocument(fixture)).isEqualTo(original);
		assertThat(first.requests).isEmpty();
	}

	@Test
	void providerIoRunsOutsideTransactionsAndBothSuccessKindsRequireEvidence() {
		Fixture fixture = fixture(inventory(FIRST, SECOND), true);
		second.behavior = request -> result(request, Outcome.ABSENT_CONFIRMED);
		Claim claim = service.claim(fixture.matching()).orElseThrow();
		assertThatThrownBy(() -> new TransactionTemplate(transactions)
			.executeWithoutResult(tx -> service.execute(claim))).isInstanceOf(IllegalStateException.class);
		assertThat(first.requests).isEmpty();
		service.execute(claim);
		ExternalCleanupState state = service.inspect(fixture.matching()).orElseThrow();
		assertThat(state.status()).isEqualTo(Status.VERIFIED);
		assertThat(state.targets()).allMatch(target -> target.confirmed() && target.receipt() != null);
		assertThat(state.targets()).extracting(ExternalCleanupState.Target::outcome)
			.containsExactly(Outcome.DELETE_CONFIRMED, Outcome.ABSENT_CONFIRMED);
	}

	@Test
	void concurrentClaimsAndRepeatedExecutionDoNotRepeatSuccessfulTargets() throws Exception {
		Fixture fixture = fixture(inventory(FIRST), true);
		var executor = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			var left = executor.submit(() -> {
				start.await();
				return service.claim(fixture.matching());
			});
			var right = executor.submit(() -> {
				start.await();
				return service.claim(fixture.matching());
			});
			start.countDown();
			var claims = List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
			assertThat(claims.stream().filter(java.util.Optional::isPresent).count()).isOne();
			Claim claim = claims.stream().flatMap(java.util.Optional::stream).findFirst().orElseThrow();
			service.execute(claim);
			service.execute(claim);
			assertThat(service.claim(fixture.matching())).isEmpty();
			assertThat(first.requests).hasSize(1);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void partialFailureRetriesOnlyFailedTargetsAndKeepsSuccessfulReceipts() {
		Fixture fixture = fixture(inventory(FIRST, SECOND), true);
		second.behavior = request -> new Result(Outcome.RETRYABLE_FAILED, request.reference(),
			request.scopeHash(), null, "SYNTHETIC_RETRY");
		Claim initial = service.claim(fixture.matching()).orElseThrow();
		service.execute(initial);
		ExternalCleanupState failed = service.inspect(fixture.matching()).orElseThrow();
		assertThat(failed.status()).isEqualTo(Status.RETRY);
		String receipt = failed.targets().getFirst().receipt();
		assertThat(service.claim(fixture.matching())).isEmpty();
		given(clock.instant()).willReturn(NOW.plusSeconds(61));
		second.behavior = request -> result(request, Outcome.DELETE_CONFIRMED);
		Claim retry = service.claim(fixture.matching()).orElseThrow();
		assertThat(retry.epoch()).isEqualTo(initial.epoch());
		service.execute(retry);
		assertThat(service.inspect(fixture.matching()).orElseThrow().status()).isEqualTo(Status.VERIFIED);
		assertThat(first.requests).hasSize(1);
		assertThat(second.requests).hasSize(2);
		assertThat(second.requests.getFirst().scopeHash()).isEqualTo(second.requests.getLast().scopeHash());
		assertThat(service.inspect(fixture.matching()).orElseThrow().targets().getFirst().receipt()).isEqualTo(receipt);
	}

	@Test
	void typedUnsupportedAndUnresolvedResponsesNeverBecomeVerified() {
		for (Outcome outcome : List.of(Outcome.BLOCKED_UNSUPPORTED, Outcome.BLOCKED_UNRESOLVED)) {
			Fixture fixture = fixture(inventory(FIRST), true);
			first.behavior = request -> new Result(outcome, request.reference(), request.scopeHash(),
				null, "UNSUPPORTED");
			service.execute(service.claim(fixture.matching()).orElseThrow());
			assertThat(service.inspect(fixture.matching()).orElseThrow().status()).isEqualTo(Status.BLOCKED);
		}
	}

	@Test
	void rejectsMismatchedOrUnsafeProviderEvidenceWithoutPersistingRawPayloads() {
		List<Function<Request, Result>> invalid = List.of(
			request -> new Result(Outcome.DELETE_CONFIRMED, request.reference(), "0".repeat(64), "receipt", null),
			request -> new Result(Outcome.DELETE_CONFIRMED, SECOND, request.scopeHash(), "receipt", null),
			request -> new Result(Outcome.DELETE_CONFIRMED, request.reference(), request.scopeHash(),
				"https://private.invalid/token", null),
			request -> new Result(Outcome.DELETE_CONFIRMED, request.reference(), request.scopeHash(), null, null),
			request -> new Result(Outcome.RETRYABLE_FAILED, request.reference(), request.scopeHash(),
				null, "private body text"), request -> null);
		for (Function<Request, Result> behavior : invalid) {
			Fixture fixture = fixture(inventory(FIRST), true);
			first.behavior = behavior;
			service.execute(service.claim(fixture.matching()).orElseThrow());
			ExternalCleanupState state = service.inspect(fixture.matching()).orElseThrow();
			assertThat(state.status()).isEqualTo(Status.BLOCKED);
			assertThat(state.targets().getFirst().failureCode()).isEqualTo("INVALID_PROVIDER_RESULT");
			assertThat(mapper.writeValueAsString(state)).doesNotContain("private.invalid", "private body text");
		}
	}

	@Test
	void expiredWorkersCannotSaveResultsOrStealAReplacementAttempt() {
		Fixture fixture = fixture(inventory(FIRST), true);
		Claim old = service.claim(fixture.matching()).orElseThrow();
		Request oldRequest = request(old, service.inspect(fixture.matching()).orElseThrow().targets().getFirst());
		given(clock.instant()).willReturn(NOW.plusSeconds(61));
		Claim replacement = service.claim(fixture.matching()).orElseThrow();
		assertThat(replacement.attempt()).isGreaterThan(old.attempt());
		assertThat(service.record(old, oldRequest, result(oldRequest, Outcome.DELETE_CONFIRMED))).isFalse();
		service.execute(old);
		assertThat(first.requests).isEmpty();
		service.execute(replacement);
		assertThat(service.inspect(fixture.matching()).orElseThrow().status()).isEqualTo(Status.VERIFIED);
	}

	@Test
	void discardsLateResultsWhenDbAttemptChangesDuringProviderIo() {
		Fixture fixture = fixture(inventory(FIRST), true);
		first.behavior = request -> {
			changeDb(fixture, state -> copy(state, state.attempt() + 1, state.inventory(), state.status()));
			return result(request, Outcome.DELETE_CONFIRMED);
		};
		service.execute(service.claim(fixture.matching()).orElseThrow());
		ExternalCleanupState state = service.inspect(fixture.matching()).orElseThrow();
		assertThat(state.status()).isEqualTo(Status.BLOCKED);
		assertThat(state.targets().getFirst().receipt()).isNull();
		assertThat(state.failureCode()).isEqualTo("DB_BINDING_CHANGED");
	}

	@Test
	void restoringDbRowsInvalidatesStoredVerificationAndRechecksEveryTargetAfterRedeletion() {
		Fixture fixture = fixture(inventory(FIRST, SECOND), true);
		service.execute(service.claim(fixture.matching()).orElseThrow());
		ExternalCleanupState original = service.inspect(fixture.matching()).orElseThrow();
		restore(fixture);
		assertThat(service.inspect(fixture.matching()).orElseThrow().status()).isEqualTo(Status.BLOCKED);
		assertThat(service.claim(fixture.matching())).isEmpty();
		var reclaimed = deletion.claimNext().orElseThrow();
		assertThat(deletion.handoff(fixture.matching()).orElseThrow().status())
			.isEqualTo(ChatDeletionState.Status.RUNNING);
		assertThat(service.claim(fixture.matching())).isEmpty();
		assertThat(deletion.deleteClaim(reclaimed)).isEqualTo(ChatDeletionService.Outcome.DELETED);
		Claim newEpoch = service.claim(fixture.matching()).orElseThrow();
		assertThat(newEpoch.epoch()).isNotEqualTo(original.epoch());
		service.execute(newEpoch);
		ExternalCleanupState current = service.inspect(fixture.matching()).orElseThrow();
		assertThat(current.status()).isEqualTo(Status.VERIFIED);
		assertThat(current.approval()).isEqualTo(original.approval());
		assertThat(current.binding().dbAttempt()).isGreaterThan(original.binding().dbAttempt());
		assertThat(current.history()).anyMatch(evidence -> evidence.status() == Status.VERIFIED
			&& evidence.targets().stream().allMatch(target -> target.receipt() != null));
		assertThat(first.requests).hasSize(2);
		assertThat(second.requests).hasSize(2);
	}

	@Test
	void evenAZeroTargetVerificationIsInvalidatedWhenTheChatReappears() {
		Fixture fixture = fixture(new Inventory(true, true, "test-absence", List.of()), true);
		assertThat(service.claim(fixture.matching())).isEmpty();
		assertThat(service.inspect(fixture.matching()).orElseThrow().status()).isEqualTo(Status.VERIFIED);
		restore(fixture);
		assertThat(service.inspect(fixture.matching()).orElseThrow().status()).isEqualTo(Status.BLOCKED);
		assertThat(service.inspect(fixture.matching()).orElseThrow().verifiedAt()).isNull();
	}

	@Test
	void blocksRunningDbRecoveryEvenWhenItsHistoricalHandoffExists() {
		Fixture fixture = fixture(inventory(FIRST), true);
		service.execute(service.claim(fixture.matching()).orElseThrow());
		changeDb(fixture,
			state -> copy(state, state.attempt() + 1, state.inventory(), ChatDeletionState.Status.RUNNING));
		assertThat(deletion.handoff(fixture.matching())).isPresent();
		assertThat(service.inspect(fixture.matching()).orElseThrow().status()).isEqualTo(Status.BLOCKED);
		assertThat(service.claim(fixture.matching())).isEmpty();
		assertThat(first.requests).hasSize(1);
	}

	@Test
	void changedApprovedScopeBlocksAndPreservesTheOriginalReferencesAndReceipts() {
		Fixture fixture = fixture(inventory(FIRST), true);
		service.execute(service.claim(fixture.matching()).orElseThrow());
		ExternalCleanupState original = service.inspect(fixture.matching()).orElseThrow();
		changeDb(fixture, state -> copy(state, state.attempt(), inventory(SECOND), state.status()));
		assertThat(service.inspect(fixture.matching()).orElseThrow().failureCode()).isEqualTo("APPROVAL_SCOPE_CHANGED");
		assertThat(service.claim(fixture.matching())).isEmpty();
		ExternalCleanupState blocked = service.inspect(fixture.matching()).orElseThrow();
		assertThat(blocked.approval()).isEqualTo(original.approval());
		assertThat(blocked.targets().getFirst().receipt()).isEqualTo(original.targets().getFirst().receipt());
		assertThat(second.requests).isEmpty();
	}

	@Test
	void duplicateInventoryReferencesDoNotAuthorizeAmbiguousCleanup() {
		Fixture fixture = fixture(inventory(FIRST), true);
		// Older DB deletion journals may already contain duplicates; cleanup must still reject them.
		changeDb(fixture, state -> copy(state, state.attempt(), inventory(FIRST, FIRST), state.status()));
		assertThat(service.claim(fixture.matching())).isEmpty();
		assertThat(service.inspect(fixture.matching()).orElseThrow().status()).isEqualTo(Status.BLOCKED);
		assertThat(first.requests).isEmpty();
	}

	@Test
	void anExternalOnlyRestoreNotificationRechecksAllTargetsButRejectsDuplicateAndOldScopes() {
		Fixture fixture = fixture(inventory(FIRST, SECOND), true);
		service.execute(service.claim(fixture.matching()).orElseThrow());
		ExternalCleanupState before = service.inspect(fixture.matching()).orElseThrow();
		var notice = restoration(fixture, before, "test-restore-event");
		assertThat(service.restored(notice)).isTrue();
		ExternalCleanupState reset = service.inspect(fixture.matching()).orElseThrow();
		assertThat(reset.status()).isEqualTo(Status.BLOCKED);
		assertThat(reset.epoch()).isNotEqualTo(before.epoch());
		assertThat(reset.binding()).isEqualTo(before.binding());
		assertThat(reset.history()).anyMatch(evidence -> evidence.status() == Status.VERIFIED);
		assertThat(service.restored(notice)).isFalse();
		service.execute(service.claim(fixture.matching()).orElseThrow());
		ExternalCleanupState verified = service.inspect(fixture.matching()).orElseThrow();
		assertThat(verified.status()).isEqualTo(Status.VERIFIED);
		assertThat(service.restored(notice)).isFalse();
		assertThat(service.restored(restoration(fixture, verified, "test-restore-event"))).isFalse();
		assertThat(service.inspect(fixture.matching()).orElseThrow().status()).isEqualTo(Status.VERIFIED);
		assertThat(first.requests).hasSize(2);
		assertThat(second.requests).hasSize(2);
	}

	@Test
	void identicalClaimsExecutingConcurrentlyDispatchEachProviderRequestOnlyOnce() throws Exception {
		Fixture fixture = fixture(inventory(FIRST), true);
		Claim claim = service.claim(fixture.matching()).orElseThrow();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		first.behavior = request -> {
			entered.countDown();
			await(release);
			return result(request, Outcome.DELETE_CONFIRMED);
		};
		var executor = Executors.newFixedThreadPool(2);
		try {
			var initial = executor.submit(() -> service.execute(claim));
			assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
			var duplicate = executor.submit(() -> service.execute(claim));
			duplicate.get(10, TimeUnit.SECONDS);
			assertThat(first.requests).hasSize(1);
			release.countDown();
			initial.get(10, TimeUnit.SECONDS);
			assertThat(service.inspect(fixture.matching()).orElseThrow().status()).isEqualTo(Status.VERIFIED);
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void restoredOrDamagedVerificationJournalsCannotClaimSuccessFromMissingOrInvalidProof() {
		List<String> corruptions = List.of(
			"jsonb_set(chat_external_cleanup_state,'{targets}','[]'::jsonb)",
			"jsonb_set(chat_external_cleanup_state,'{targets,0,scopeHash}',to_jsonb(repeat('0',64)))",
			"jsonb_set(chat_external_cleanup_state,'{targets,0,outcome}','\"RETRYABLE_FAILED\"'::jsonb)",
			"jsonb_set(chat_external_cleanup_state,'{verifiedAt}','null'::jsonb)");
		for (String corruption : corruptions) {
			Fixture fixture = fixture(inventory(FIRST), true);
			service.execute(service.claim(fixture.matching()).orElseThrow());
			int calls = first.requests.size();
			// Expressions are fixed test constants and can never originate from a request or provider.
			jdbc.update("update matching set chat_external_cleanup_state=" + corruption + " where id=?",
				fixture.matching());
			assertThat(service.inspect(fixture.matching()).orElseThrow().status()).isEqualTo(Status.BLOCKED);
			assertThat(service.claim(fixture.matching())).isEmpty();
			assertThat(first.requests).hasSize(calls);
		}
	}

	@Test
	void rejectsMissingTargetsInRunningJournalsInsteadOfApprovingZeroTargets() {
		Fixture fixture = fixture(inventory(FIRST), true);
		service.claim(fixture.matching()).orElseThrow();
		jdbc.update("update matching set chat_external_cleanup_state="
			+ "jsonb_set(chat_external_cleanup_state,'{targets}','[]'::jsonb) where id=?", fixture.matching());
		assertThat(service.claim(fixture.matching())).isEmpty();
		assertThat(service.inspect(fixture.matching()).orElseThrow().failureCode())
			.isEqualTo("INVALID_CLEANUP_JOURNAL");
		assertThat(first.requests).isEmpty();
	}

	private Fixture fixture(Inventory inventory, boolean deleteDb) {
		long firstUser = user();
		long secondUser = user();
		long matching = jdbc.queryForObject("insert into matching(user_low_id,user_high_id,status) "
			+ "values (?,?,'CLOSED') returning id", Long.class, firstUser, secondUser);
		Instant closed = NOW.minus(Duration.ofHours(720));
		long chat = jdbc.queryForObject("insert into chat(matching_id,created_at,expires_at,closed_at,"
			+ "last_message_seq,icebreakers,ai_state) values (?,?,?,?,0,'{}','{}') returning id", Long.class,
			matching, Timestamp.from(closed.minus(Duration.ofHours(168))),
			Timestamp.from(closed), Timestamp.from(closed));
		String backup = jdbc.queryForObject("select to_jsonb(c)::text from chat c where id=?", String.class, chat);
		Fixture fixture = new Fixture(matching, chat, firstUser, secondUser, backup);
		fixtures.add(fixture);
		approved.put(matching, inventory);
		if (deleteDb) {
			assertThat(deletion.deleteClaim(deletion.claimNext().orElseThrow()))
				.isEqualTo(ChatDeletionService.Outcome.DELETED);
		}
		return fixture;
	}

	private long user() {
		return jdbc.queryForObject("insert into users(provider,provider_user_id,status,created_at) "
			+ "values ('GOOGLE',?,'ACTIVE',?) returning id", Long.class,
			UUID.randomUUID().toString(), Timestamp.from(NOW));
	}

	private void restore(Fixture fixture) {
		jdbc.update("insert into chat overriding system value "
			+ "select * from jsonb_populate_record(null::chat,cast(? as jsonb))", fixture.backup());
	}

	private void changeDb(Fixture fixture, UnaryOperator<ChatDeletionState> change) {
		new TransactionTemplate(transactions).executeWithoutResult(tx -> {
			deletions.lockMatching(fixture.matching());
			deletions.save(fixture.matching(), change.apply(deletions.state(fixture.matching()).orElseThrow()));
		});
	}

	private String dbDocument(Fixture fixture) {
		return jdbc.queryForObject("select chat_deletion_state::text from matching where id=?", String.class,
			fixture.matching());
	}

	private static Inventory inventory(Reference... targets) {
		return new Inventory(true, false, "test-approval", List.of(targets));
	}

	private static ChatDeletionState copy(ChatDeletionState state, int attempt, Inventory inventory,
		ChatDeletionState.Status status) {
		return new ChatDeletionState(state.schemaVersion(), state.generation(), state.context(), status, attempt,
			state.leaseUntil(), state.nextAttemptAt(), inventory, state.failureCode(), state.dbDeletedAt());
	}

	private static Request request(Claim claim, ExternalCleanupState.Target target) {
		return new Request(claim.matchingId(), claim.epoch(), claim.attempt(), target.dispatchId(), claim.binding(),
			"test-approval", target.reference(), target.scopeHash());
	}

	private static Result result(Request request, Outcome outcome) {
		return new Result(outcome, request.reference(), request.scopeHash(),
			"receipt-" + request.reference().opaqueId(), null);
	}

	private static ExternalCleanupService.Restoration restoration(Fixture fixture, ExternalCleanupState state,
		String id) {
		return new ExternalCleanupService.Restoration(fixture.matching(), state.epoch(),
			state.binding().inventoryHash(),
			state.targets().getFirst().reference(), state.targets().getFirst().scopeHash(), id);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Synthetic provider wait timed out");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Synthetic provider interrupted", exception);
		}
	}

	private record Fixture(long matching, long chat, long firstUser, long secondUser, String backup) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Providers {
		@Bean
		FakeProvider syntheticFirst() {
			return new FakeProvider("synthetic-a");
		}

		@Bean
		FakeProvider syntheticSecond() {
			return new FakeProvider("synthetic-b");
		}
	}

	static class FakeProvider implements ExternalCopyDeletionPort {
		private final String provider;
		private final List<Request> requests = new CopyOnWriteArrayList<>();
		private Function<Request, Result> behavior;

		FakeProvider(String provider) {
			this.provider = provider;
			reset();
		}

		void reset() {
			requests.clear();
			behavior = request -> result(request, Outcome.DELETE_CONFIRMED);
		}

		@Override
		public String provider() {
			return provider;
		}

		@Override
		public String contractVersion() {
			return "v1";
		}

		@Override
		public Result deleteAndVerify(Request request) {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			requests.add(request);
			return behavior.apply(request);
		}
	}
}
