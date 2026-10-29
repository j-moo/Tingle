package com.e106.tingle.proposal.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.MatchingParticipant;
import com.e106.tingle.proposal.MatchingParticipantRepository;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.explanation.dto.ExplanationStatus;
import com.e106.tingle.proposal.explanation.dto.ExplanationTask;

import tools.jackson.databind.JsonNode;

/**
 * 실제 JWT·PostgreSQL에서 BE 실행 수명과 결과 공개 경계를 검증한다.
 * provider는 합성 규칙 결과로 대체하며 실제 AI·당시 판정 해석·공개 근거 검증의 증거로 사용하지 않는다.
 */
@SpringBootTest(properties = "app.match-explanations.scheduling-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ExplanationIntegrationTest extends IntegrationTestSupport {

	private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
	private static final String EVALUATION = """
		{"ruleVersion":"synthetic-v1","decision":"MATCHED","preserved":{"score":71}}
		""";
	@Autowired
	private ExplanationService service;
	@Autowired
	private ExplanationTransactions transactions;
	@Autowired
	private ExplanationRecovery recovery;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private ProposalRepository proposals;
	@Autowired
	private MatchingParticipantRepository participants;
	@MockitoBean
	private RuleExplanationProvider provider;
	private JsonNode firstTokens;
	private JsonNode secondTokens;
	private long firstId;
	private long secondId;
	private long proposalId;

	@BeforeEach
	void preparePair() throws Exception {
		given(clock.instant()).willReturn(NOW);
		firstTokens = login("kakao", newSocialId());
		secondTokens = login("kakao", newSocialId());
		completeOnboarding(firstTokens);
		completeOnboarding(secondTokens);
		firstId = userIdOf(firstTokens);
		secondId = userIdOf(secondTokens);
		MatchProposal proposal = proposals.saveAndFlush(MatchProposal.proposed(firstId, secondId, NOW));
		proposalId = proposal.getId();
		participants.saveAllAndFlush(List.of(new MatchingParticipant(proposalId, firstId),
			new MatchingParticipant(proposalId, secondId)));
		jdbc.update("update matching set evaluation=cast(? as jsonb) where id=?", EVALUATION, proposalId);
	}

	@Test
	void actualAuthenticatedRequestsPersistAndReadOnlyTheViewerResultWithoutReplacingOriginalEvidence()
		throws Exception {
		given(provider.explain(any())).willAnswer(invocation -> {
			assertProviderOutsideTransaction();
			RuleExplanationProvider.Input input = invocation.getArgument(0);
			return succeeded(input.viewerId() == firstId ? "synthetic first" : "synthetic second");
		});
		perform(post(requestPath()).header("Idempotency-Key", UUID.randomUUID()), secondTokens, null)
			.andExpect(status().isAccepted());
		perform(post(requestPath()).header("Idempotency-Key", UUID.randomUUID()), firstTokens, null)
			.andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.proposalId").value(proposalId))
			.andExpect(jsonPath("$.kind").value("MATCH_EXPLANATION"))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.jobId").doesNotExist());
		perform(get(resultPath()), firstTokens, null).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.source").value("RULE_FALLBACK"))
			.andExpect(jsonPath("$.text").value("synthetic first"))
			.andExpect(jsonPath("$.evidenceKeys[0]").value("synthetic.rule"))
			.andExpect(jsonPath("$.promptVersion").isEmpty());
		perform(get(resultPath()), secondTokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.text").value("synthetic second"));
		assertThat(service.get(proposalId, firstId).text()).isEqualTo("synthetic first");
		assertThat(service.get(proposalId, secondId).text()).isEqualTo("synthetic second");
		assertOriginalEvaluationPreserved();
		verify(provider, times(2)).explain(any());
	}

	@Test
	void authenticationAndOwnershipPreventUnauthorizedGenerationAndResultAccess() throws Exception {
		mockMvc.perform(get(resultPath())).andExpect(status().isUnauthorized());
		mockMvc.perform(post(requestPath()).header("Idempotency-Key", UUID.randomUUID()))
			.andExpect(status().isUnauthorized());
		JsonNode outsider = login("kakao", newSocialId());
		completeOnboarding(outsider);
		perform(get(resultPath()), outsider, null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		perform(post(requestPath()).header("Idempotency-Key", UUID.randomUUID()), outsider, null)
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		perform(post(requestPath()), firstTokens, null).andExpect(status().isBadRequest());
		verifyNoInteractions(provider);
	}

	@Test
	void completedResultIsReusedForSameOrDifferentRequestKeys() {
		given(provider.explain(any())).willReturn(succeeded("synthetic stable"));
		UUID key = UUID.randomUUID();
		assertThat(service.request(proposalId, firstId, key).status()).isEqualTo("SUCCEEDED");
		String stored = storedEvaluation();
		assertThat(service.request(proposalId, firstId, key).status()).isEqualTo("SUCCEEDED");
		assertThat(storedEvaluation()).isEqualTo(stored);
		assertThat(service.request(proposalId, firstId, UUID.randomUUID()).status()).isEqualTo("SUCCEEDED");
		assertThat(service.get(proposalId, firstId).text()).isEqualTo("synthetic stable");
		assertOriginalEvaluationPreserved();
		verify(provider).explain(any());
	}

	@Test
	void failedResultAlsoPreventsUnapprovedProviderRetriesWithNewKeys() {
		given(provider.explain(any())).willReturn(failed());
		assertThat(service.request(proposalId, firstId, UUID.randomUUID()).status()).isEqualTo("FAILED");
		assertThat(service.request(proposalId, firstId, UUID.randomUUID()).status()).isEqualTo("FAILED");
		assertThat(service.get(proposalId, firstId).status()).isEqualTo("FAILED");
		assertOriginalEvaluationPreserved();
		verify(provider).explain(any());
	}

	@Test
	void concurrentRequestSeesCommittedPendingAndCallsProviderOnlyOnce() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		given(provider.explain(any())).willAnswer(invocation -> {
			assertProviderOutsideTransaction();
			entered.countDown();
			if (!release.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("synthetic provider was not released");
			}
			return succeeded("synthetic concurrent");
		});
		try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
			Future<ExplanationTask> first = worker.submit(() -> service.request(proposalId, firstId,
				UUID.randomUUID()));
			try {
				assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
				assertThat(service.request(proposalId, firstId, UUID.randomUUID()).status()).isEqualTo("PENDING");
				assertThat(service.get(proposalId, firstId).status()).isEqualTo("PENDING");
				verify(provider).explain(any());
			} finally {
				release.countDown();
			}
			assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo("SUCCEEDED");
		}
		assertThat(service.get(proposalId, firstId).text()).isEqualTo("synthetic concurrent");
		verify(provider).explain(any());
	}

	@Test
	void sameRequestKeyCannotBeReboundToAChangedEvaluation() throws Exception {
		given(provider.explain(any())).willReturn(succeeded("synthetic original"));
		UUID key = UUID.randomUUID();
		service.request(proposalId, firstId, key);
		changeEvaluationVersion();
		perform(post(requestPath()).header("Idempotency-Key", key), firstTokens, null)
			.andExpect(status().isConflict());
		assertThat(service.get(proposalId, firstId).status()).isEqualTo("FAILED");
		verify(provider).explain(any());
	}

	@Test
	void delayedProviderResultCannotOverwriteAChangedEvaluation() {
		given(provider.explain(any())).willAnswer(invocation -> {
			assertProviderOutsideTransaction();
			changeEvaluationVersion();
			return succeeded("synthetic stale");
		});
		service.request(proposalId, firstId, UUID.randomUUID());
		assertThat(service.get(proposalId, firstId).status()).isEqualTo("FAILED");
		assertThat(service.get(proposalId, firstId).text()).isNull();
		assertThat(jsonMapper.readTree(storedEvaluation()).path("ruleVersion").asString())
			.isEqualTo("synthetic-v2");
		assertThat(jsonMapper.readTree(storedEvaluation()).path("preserved").path("score").asInt()).isEqualTo(71);
	}

	@Test
	void policyGenerationChangedDuringProviderCallInvalidatesAnOtherwiseIdenticalEvaluation() {
		given(provider.explain(any())).willAnswer(invocation -> {
			assertProviderOutsideTransaction();
			jdbc.update("update matching set policy_version='new-policy' where id=?", proposalId);
			return succeeded("synthetic old policy");
		});
		assertThat(service.request(proposalId, firstId, UUID.randomUUID()).status()).isEqualTo("FAILED");
		assertThat(service.get(proposalId, firstId)).isEqualTo(failed());
		assertThat(jdbc.queryForObject("select policy_version from matching where id=?",
			String.class, proposalId)).isEqualTo("new-policy");
		assertOriginalEvaluationPreserved();
		assertThat(storedEvaluation()).doesNotContain("synthetic old policy");
		verify(provider).explain(any());
	}

	@Test
	void participantBindingChangedDuringProviderCallInvalidatesAnOtherwiseIdenticalEvaluation() throws Exception {
		JsonNode replacementTokens = login("kakao", newSocialId());
		completeOnboarding(replacementTokens);
		long replacementId = userIdOf(replacementTokens);
		given(provider.explain(any())).willAnswer(invocation -> {
			assertProviderOutsideTransaction();
			jdbc.update("update matching set user_high_id=? where id=?", replacementId, proposalId);
			return succeeded("synthetic old participant");
		});
		assertThat(service.request(proposalId, firstId, UUID.randomUUID()).status()).isEqualTo("FAILED");
		assertThat(service.get(proposalId, firstId)).isEqualTo(failed());
		assertThat(jdbc.queryForObject("select user_high_id from matching where id=?",
			Long.class, proposalId)).isEqualTo(replacementId);
		assertOriginalEvaluationPreserved();
		assertThat(storedEvaluation()).doesNotContain("synthetic old participant");
		verify(provider).explain(any());
	}

	@Test
	void blockingDuringProviderCallPreventsPublishingItsLateResult() {
		given(provider.explain(any())).willAnswer(invocation -> {
			assertProviderOutsideTransaction();
			jdbc.update("update matching_participant set blocked_at=? where matching_id=? and user_id=?",
				Timestamp.from(NOW), proposalId, firstId);
			return succeeded("synthetic blocked");
		});
		assertError(() -> service.request(proposalId, firstId, UUID.randomUUID()), ErrorCode.PROPOSAL_EXPIRED);
		assertError(() -> service.get(proposalId, firstId), ErrorCode.PROPOSAL_EXPIRED);
		assertThat(storedEvaluation()).doesNotContain("synthetic blocked");
	}

	@Test
	void exactExpiryDuringProviderCallPreventsPublishingItsLateResult() {
		given(provider.explain(any())).willAnswer(invocation -> {
			assertProviderOutsideTransaction();
			given(clock.instant()).willReturn(NOW.plus(Duration.ofHours(24)));
			return succeeded("synthetic expired");
		});
		assertError(() -> service.request(proposalId, firstId, UUID.randomUUID()), ErrorCode.PROPOSAL_EXPIRED);
		assertError(() -> service.get(proposalId, firstId), ErrorCode.PROPOSAL_EXPIRED);
		assertThat(storedEvaluation()).doesNotContain("synthetic expired");
	}

	@Test
	void providerExceptionIsStoredAsFailureWithoutExposingInternalContent() {
		given(provider.explain(any())).willAnswer(invocation -> {
			assertProviderOutsideTransaction();
			throw new IllegalStateException("synthetic internal detail");
		});
		assertThat(service.request(proposalId, firstId, UUID.randomUUID()).status()).isEqualTo("FAILED");
		ExplanationStatus result = service.get(proposalId, firstId);
		assertThat(result).isEqualTo(failed());
		assertThat(storedEvaluation()).doesNotContain("synthetic internal detail");
		assertOriginalEvaluationPreserved();
	}

	@Test
	void getBeforeRequestReturnsClosedFailureWithoutChangingTheMatchingRow() throws Exception {
		String before = storedEvaluation();
		perform(get(resultPath()), firstTokens, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.text").isEmpty()).andExpect(jsonPath("$.source").isEmpty())
			.andExpect(jsonPath("$.evidenceKeys").isEmpty());
		assertThat(storedEvaluation()).isEqualTo(before);
		verifyNoInteractions(provider);
	}

	@Test
	void acceptanceClockIsCapturedAfterSuccessfulCommitAndPersistedBeforeProviderExecution() {
		ExplanationTransactions.Lease lease = new TransactionTemplate(transactionManager).execute(status -> {
			ExplanationTransactions.Lease pending = transactions.begin(proposalId, firstId, UUID.randomUUID());
			assertThat(pending.run().acceptedAt()).isNull();
			given(clock.instant()).willReturn(NOW.plusSeconds(10));
			return pending;
		});
		assertThat(transactions.accept(proposalId, firstId, lease).status()).isEqualTo("PENDING");
		assertThat(storedRun(firstId).path("acceptedAt").asString()).isEqualTo(NOW.plusSeconds(10).toString());
		assertThat(storedRun(firstId).path("deadlineAt").asString()).isEqualTo(NOW.plusSeconds(310).toString());
		given(clock.instant()).willReturn(NOW.plusSeconds(50));
		transactions.accept(proposalId, firstId, lease);
		assertThat(storedRun(firstId).path("acceptedAt").asString()).isEqualTo(NOW.plusSeconds(10).toString());
		assertThat(service.request(proposalId, firstId, UUID.randomUUID()).status()).isEqualTo("PENDING");
		assertThat(storedRun(firstId).path("deadlineAt").asString()).isEqualTo(NOW.plusSeconds(310).toString());
		verifyNoInteractions(provider);
	}

	@Test
	void requestInsideAnUncommittedOuterTransactionNeverCallsTheProvider() {
		String before = storedEvaluation();
		assertError(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
			service.request(proposalId, firstId, UUID.randomUUID())), ErrorCode.SERVICE_UNAVAILABLE);
		verifyNoInteractions(provider);
		assertThat(storedEvaluation()).isEqualTo(before);
	}

	@Test
	void exactDeadlineReadPersistsFailureAndAnotherKeyCannotExtendOrRerunIt() {
		ExplanationTransactions.Lease lease = transactions.begin(proposalId, firstId, UUID.randomUUID());
		transactions.accept(proposalId, firstId, lease);
		given(clock.instant()).willReturn(NOW.plusSeconds(300).minusNanos(1));
		assertThat(service.get(proposalId, firstId).status()).isEqualTo("PENDING");
		given(clock.instant()).willReturn(NOW.plusSeconds(300));
		assertThat(service.get(proposalId, firstId).status()).isEqualTo("FAILED");
		assertThat(storedRun(firstId).path("status").asString()).isEqualTo("FAILED");
		assertThat(service.request(proposalId, firstId, UUID.randomUUID()).status()).isEqualTo("FAILED");
		assertThat(storedRun(firstId).path("deadlineAt").asString()).isEqualTo(NOW.plusSeconds(300).toString());
		assertOriginalEvaluationPreserved();
		verifyNoInteractions(provider);
	}

	@Test
	void providerResultAtTheFiveMinuteDeadlineCannotReviveTheExpiredAttempt() {
		given(provider.explain(any())).willAnswer(invocation -> {
			assertProviderOutsideTransaction();
			assertThat(storedRun(firstId).path("acceptedAt").asString()).isEqualTo(NOW.toString());
			given(clock.instant()).willReturn(NOW.plusSeconds(300));
			return succeeded("synthetic too late");
		});
		assertThat(service.request(proposalId, firstId, UUID.randomUUID()).status()).isEqualTo("FAILED");
		assertThat(service.get(proposalId, firstId)).isEqualTo(failed());
		assertThat(storedEvaluation()).doesNotContain("synthetic too late");
		assertOriginalEvaluationPreserved();
		verify(provider).explain(any());
	}

	@Test
	void restartRecoveryClosesAnOrphanReservationWithoutInventingANewAcceptanceTime() {
		ExplanationTransactions.Lease lease = transactions.begin(proposalId, firstId, UUID.randomUUID());
		assertThat(storedRun(firstId).path("acceptedAt").isNull()).isTrue();
		recovery.afterRestart();
		assertThat(storedRun(firstId).path("status").asString()).isEqualTo("PENDING");
		given(clock.instant()).willReturn(NOW.plusSeconds(300));
		recovery.afterRestart();
		assertThat(storedRun(firstId).path("status").asString()).isEqualTo("FAILED");
		given(clock.instant()).willReturn(NOW.plusSeconds(340));
		assertThat(transactions.accept(proposalId, firstId, lease).status()).isEqualTo("FAILED");
		assertThat(service.request(proposalId, firstId, UUID.randomUUID()).status()).isEqualTo("FAILED");
		assertThat(storedRun(firstId).path("acceptedAt").isNull()).isTrue();
		assertOriginalEvaluationPreserved();
		verifyNoInteractions(provider);
	}

	@Test
	void concurrentReadsDuplicatesAndRecoveryInTheCommitGapDoNotCancelTheOriginalReservation() {
		ExplanationTransactions.Lease lease = transactions.begin(proposalId, firstId, UUID.randomUUID());
		assertThat(service.get(proposalId, firstId).status()).isEqualTo("PENDING");
		assertThat(service.request(proposalId, firstId, UUID.randomUUID()).status()).isEqualTo("PENDING");
		recovery.recover();
		assertThat(service.get(proposalId, firstId).status()).isEqualTo("PENDING");
		assertThat(storedRun(firstId).path("reservedAt").asString()).isEqualTo(NOW.toString());
		verifyNoInteractions(provider);
		assertThat(transactions.accept(proposalId, firstId, lease).status()).isEqualTo("PENDING");
		assertThat(transactions.complete(proposalId, firstId, lease.run(), succeeded("synthetic accepted")))
			.isEqualTo(succeeded("synthetic accepted"));
		assertThat(service.get(proposalId, firstId).text()).isEqualTo("synthetic accepted");
		assertThat(storedRun(firstId).path("deadlineAt").asString()).isEqualTo(NOW.plusSeconds(300).toString());
		assertOriginalEvaluationPreserved();
	}

	@Test
	void restartAndPeriodicRecoveryUseStoredDeadlineAndPreserveThePeerResult() {
		given(provider.explain(any())).willReturn(succeeded("synthetic peer kept"));
		service.request(proposalId, secondId, UUID.randomUUID());
		ExplanationTransactions.Lease lease = transactions.begin(proposalId, firstId, UUID.randomUUID());
		transactions.accept(proposalId, firstId, lease);
		given(clock.instant()).willReturn(NOW.plusSeconds(299));
		recovery.afterRestart();
		assertThat(storedRun(firstId).path("status").asString()).isEqualTo("PENDING");
		given(clock.instant()).willReturn(NOW.plusSeconds(300));
		recovery.recover();
		assertThat(storedRun(firstId).path("status").asString()).isEqualTo("FAILED");
		assertThat(storedRun(firstId).path("deadlineAt").asString()).isEqualTo(NOW.plusSeconds(300).toString());
		assertThat(service.get(proposalId, secondId).text()).isEqualTo("synthetic peer kept");
		assertThat(transactions.complete(proposalId, firstId, lease.run(), succeeded("synthetic late")))
			.isEqualTo(failed());
		assertOriginalEvaluationPreserved();
		verify(provider).explain(any());
	}

	private JsonNode storedRun(long viewer) {
		return jsonMapper.readTree(storedEvaluation()).path(ExplanationJournal.FIELD).path("viewers")
			.path(Long.toString(viewer)).path("current");
	}

	private void changeEvaluationVersion() {
		jdbc.update("update matching set evaluation=jsonb_set(evaluation,'{ruleVersion}',"
			+ "'\"synthetic-v2\"'::jsonb) where id=?", proposalId);
	}

	private void assertOriginalEvaluationPreserved() {
		String remaining = jdbc.queryForObject("select (evaluation-'matchExplanations')::text "
			+ "from matching where id=?", String.class, proposalId);
		assertThat(jsonMapper.readTree(remaining)).isEqualTo(jsonMapper.readTree(EVALUATION));
	}

	private String storedEvaluation() {
		return jdbc.queryForObject("select evaluation::text from matching where id=?", String.class, proposalId);
	}

	private String requestPath() {
		return "/api/match-proposals/" + proposalId + "/explanations";
	}

	private String resultPath() {
		return "/api/match-proposals/" + proposalId + "/explanation";
	}

	private static ExplanationStatus succeeded(String text) {
		return new ExplanationStatus("SUCCEEDED", "RULE_FALLBACK", text, List.of("synthetic.rule"), null);
	}

	private static ExplanationStatus failed() {
		return new ExplanationStatus("FAILED", null, null, List.of(), null);
	}

	private static void assertProviderOutsideTransaction() {
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
	}

	private static void assertError(Runnable call, ErrorCode expected) {
		assertThatThrownBy(call::run).isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode()).isEqualTo(expected);
	}
}
