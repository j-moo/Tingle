package com.e106.tingle.proposal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.proposal.ProposalRepository;

/** 일회용 PostgreSQL의 실제 JSONB/행 잠금 검증. 실제 원본 생산자 검증기는 합성 대체다. */
@SpringBootTest
@ActiveProfiles({"test", "erd"})
@Import(TestcontainersConfiguration.class)
class HistoricalEvaluationPersistenceTest {
	@Autowired
	private HistoricalEvaluationStore store;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private ProposalRepository proposals;
	@Autowired
	private PlatformTransactionManager transactions;
	@MockitoBean
	private HistoricalEvidenceVerifier verifier;
	private long matchingId;
	private long low;
	private long high;

	@BeforeEach
	void prepare() {
		// 다른 컨텍스트의 자료를 지우지 않고 각 검증의 고유 matching 행만 쓴다.
		low = jdbc.queryForObject("select coalesce(max(user_high_id),100000)+100 from matching", Long.class);
		high = low + 1;
		matchingId = jdbc.queryForObject("insert into matching(user_low_id,user_high_id,met_at,policy_version,"
			+ "status,proposed_at,evaluation) values(?,?,?,'policy-test-1','PENDING',?,"
			+ "'{\"decision\":\"SYNTHETIC_CONFIRMED\",\"score\":0.7,\"matchExplanations\":"
			+ "{\"peer\":\"preserve\"}}'::jsonb) returning id",
			Long.class, low, high, Timestamp.from(HistoricalEvidenceFixture.MET),
			Timestamp.from(HistoricalEvidenceFixture.MET));
		when(verifier.verify(any(), any())).thenReturn(true);
	}

	@Test
	void roundTripPreservesOriginalDecisionPeerAndOriginalLeftRight() {
		HistoricalEvaluationEvidence evidence = evidence();
		store.store(evidence);
		assertThat(store.find(matchingId)).contains(evidence);
		assertThat(jdbc.queryForObject("select evaluation->>'decision' from matching where id=?",
			String.class, matchingId)).isEqualTo("SYNTHETIC_CONFIRMED");
		assertThat(jdbc.queryForObject("select evaluation->'matchExplanations'->>'peer' from matching where id=?",
			String.class, matchingId)).isEqualTo("preserve");
		assertThat(store.find(matchingId)).contains(evidence);
		assertThat(evidence.left().userId()).isEqualTo(high);
		assertThat(evidence.displayEvidenceIdsByViewer().get("A")).containsExactly("e1");
	}

	@Test
	void equivalentConcurrentRetryKeepsOneImmutableCapture() throws Exception {
		HistoricalEvaluationEvidence evidence = evidence();
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> {
				start.await();
				return store.store(evidence);
			});
			var second = executor.submit(() -> {
				start.await();
				return store.store(evidence);
			});
			start.countDown();
			assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(evidence);
			assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(evidence);
		}
		assertThat(store.find(matchingId)).contains(evidence);
	}

	@Test
	void changedCaptureAndStaleMatchingCannotReplaceTheOriginal() {
		HistoricalEvaluationEvidence evidence = evidence();
		store.store(evidence);
		HistoricalEvaluationEvidence changed = new HistoricalEvaluationEvidence(evidence.schemaVersion(),
			evidence.matchingId(), evidence.metAt(), evidence.policyVersion(), evidence.left(), evidence.right(),
			evidence.manifest(), "other-display-version", evidence.sourceEvidence(),
			evidence.displayEvidenceIdsByViewer(), evidence.conflictingSourceRefsByViewer());
		assertThatThrownBy(() -> store.store(changed)).isInstanceOf(BusinessException.class);
		assertThat(store.find(matchingId)).contains(evidence);
		jdbc.update("update matching set policy_version='other-generation' where id=?", matchingId);
		assertThat(store.find(matchingId)).isEmpty();
		assertThatThrownBy(() -> store.store(evidence)).isInstanceOf(BusinessException.class);
	}

	@Test
	void deniedProofAndOuterRollbackLeaveNoHistoricalCapture() {
		HistoricalEvaluationEvidence evidence = evidence();
		when(verifier.verify(any(), any())).thenReturn(false);
		assertThatThrownBy(() -> store.store(evidence)).isInstanceOf(BusinessException.class);
		assertThat(store.find(matchingId)).isEmpty();
		when(verifier.verify(any(), any())).thenReturn(true);
		new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			store.store(evidence);
			transaction.setRollbackOnly();
		});
		assertThat(jdbc.queryForObject("select jsonb_exists(evaluation,'historicalEvidence') from matching where id=?",
			Boolean.class, matchingId)).isFalse();
	}

	@Test
	void lossOfSourceProofStopsDisclosureAndDoesNotRewriteStoredFacts() {
		HistoricalEvaluationEvidence evidence = evidence();
		store.store(evidence);
		when(verifier.verify(any(), any())).thenReturn(false);
		assertThat(store.find(matchingId)).isEmpty();
		when(verifier.verify(any(), any())).thenReturn(true);
		assertThat(store.find(matchingId)).contains(evidence);
	}

	@Test
	void staleOuterContextCannotReplaceAnotherCommittedCaptureOrPeerState() {
		HistoricalEvaluationEvidence first = evidence();
		HistoricalEvaluationEvidence winner = new HistoricalEvaluationEvidence(first.schemaVersion(),
			first.matchingId(), first.metAt(), first.policyVersion(), first.left(), first.right(), first.manifest(),
			"committed-display-version", first.sourceEvidence(), first.displayEvidenceIdsByViewer(),
			first.conflictingSourceRefsByViewer());
		assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			proposals.findById(matchingId).orElseThrow();
			commitFromAnotherTransaction(winner);
			store.store(first);
		})).isInstanceOf(BusinessException.class);
		assertThat(store.find(matchingId)).contains(winner);
		assertPeerCommitted();
	}

	@Test
	void staleOuterContextEquivalentRetryAlsoPreservesCommittedPeerState() {
		HistoricalEvaluationEvidence evidence = evidence();
		new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			proposals.findById(matchingId).orElseThrow();
			commitFromAnotherTransaction(evidence);
			assertThat(store.store(evidence)).isEqualTo(evidence);
		});
		assertThat(store.find(matchingId)).contains(evidence);
		assertPeerCommitted();
	}

	private void commitFromAnotherTransaction(HistoricalEvaluationEvidence evidence) {
		try (var executor = Executors.newSingleThreadExecutor()) {
			executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
				store.store(evidence);
				var matching = proposals.findById(matchingId).orElseThrow();
				var next = new LinkedHashMap<>(matching.getEvaluation());
				next.put("matchExplanations", Map.of("peer", "committed"));
				ReflectionTestUtils.setField(matching, "evaluation", next);
			})).get(20, TimeUnit.SECONDS);
		} catch (Exception exception) {
			throw new IllegalStateException("Synthetic concurrent writer did not finish", exception);
		}
	}

	private void assertPeerCommitted() {
		assertThat(jdbc.queryForObject("select evaluation->'matchExplanations'->>'peer' from matching where id=?",
			String.class, matchingId)).isEqualTo("committed");
	}

	private HistoricalEvaluationEvidence evidence() {
		return HistoricalEvidenceFixture.evidence(matchingId, high, low);
	}
}
