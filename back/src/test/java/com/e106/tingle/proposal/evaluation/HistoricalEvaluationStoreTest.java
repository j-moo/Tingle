package com.e106.tingle.proposal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.ProposalRepository;

import jakarta.persistence.EntityManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 합성 검증기/자료이며 실제 평가 엔진·snapshot 검증 완료를 뜻하지 않는다. */
class HistoricalEvaluationStoreTest {
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final ProposalRepository proposals = mock(ProposalRepository.class);
	private final EntityManager entityManager = mock(EntityManager.class);

	@Test
	void absentFalseOrThrowingVerifierCannotReadOrWrite() {
		MatchProposal matching = matching();
		ReflectionTestUtils.setField(matching, "evaluation", stored(HistoricalEvidenceFixture.evidence(7)));
		when(proposals.lockById(7)).thenReturn(Optional.of(matching));
		when(proposals.findById(7L)).thenReturn(Optional.of(matching));
		for (HistoricalEvidenceVerifier verifier : new HistoricalEvidenceVerifier[] {
			null, (row, evidence) -> false, (row, evidence) -> {
				throw new IllegalStateException();
			}
		}) {
			HistoricalEvaluationStore store = new HistoricalEvaluationStore(proposals, mapper, verifier, entityManager);
			assertThatThrownBy(() -> store.store(HistoricalEvidenceFixture.evidence(7)))
				.isInstanceOf(BusinessException.class);
			assertThat(store.find(7)).isEmpty();
		}
	}

	@Test
	void missingActualDecisionIsNeverCreatedFromTheEvidence() {
		MatchProposal matching = matching();
		when(proposals.lockById(7)).thenReturn(Optional.of(matching));
		HistoricalEvaluationStore store = store();
		for (Map<String, Object> evaluation : List.of(Map.<String, Object>of(),
			Map.<String, Object>of("matchExplanations", Map.of("existing", "preserve")))) {
			ReflectionTestUtils.setField(matching, "evaluation", evaluation);
			assertThatThrownBy(() -> store.store(HistoricalEvidenceFixture.evidence(7)))
				.isInstanceOf(BusinessException.class);
			assertThat(matching.getEvaluation()).isEqualTo(evaluation);
		}
	}

	@Test
	void mismatchedPairTimeOrPolicyCannotBind() {
		MatchProposal matching = matching();
		when(proposals.lockById(7)).thenReturn(Optional.of(matching));
		HistoricalEvaluationStore store = store();
		for (Map.Entry<String, Object> mutation : Map.<String, Object>of("userLow", 33L, "metAt",
			Instant.EPOCH, "policyVersion", "other").entrySet()) {
			MatchProposal changed = matching();
			ReflectionTestUtils.setField(changed, mutation.getKey(), mutation.getValue());
			when(proposals.lockById(7)).thenReturn(Optional.of(changed));
			assertThatThrownBy(() -> store.store(HistoricalEvidenceFixture.evidence(7)))
				.isInstanceOf(BusinessException.class);
		}
	}

	@Test
	void malformedUnknownFieldsAndWrongGenerationFailClosedOnRead() {
		MatchProposal matching = matching();
		when(proposals.findById(7L)).thenReturn(Optional.of(matching));
		HistoricalEvaluationStore store = store();
		Map<String, Object> payload = payload(HistoricalEvidenceFixture.evidence(7));
		payload.put("unknown", "must-not-accept");
		ReflectionTestUtils.setField(matching, "evaluation", Map.of("decision", "SYNTHETIC",
			"historicalEvidence", payload));
		assertThat(store.find(7)).isEmpty();
		ReflectionTestUtils.setField(matching, "evaluation", stored(HistoricalEvidenceFixture.evidence(7)));
		ReflectionTestUtils.setField(matching, "policyVersion", "changed");
		assertThat(store.find(7)).isEmpty();
		when(proposals.findById(99L)).thenReturn(Optional.empty());
		assertThat(store.find(99)).isEmpty();
		assertThat(store.find(0)).isEmpty();
	}

	@Test
	void scopeAndFactsAreBoundedAndImmutable() {
		HistoricalEvaluationEvidence evidence = HistoricalEvidenceFixture.evidence(7);
		assertThat(evidence.left().userId()).isEqualTo(22);
		assertThat(evidence.right().userId()).isEqualTo(11);
		assertThatThrownBy(() -> evidence.manifest().put("releaseId", "other"))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> evidence.displayEvidenceIdsByViewer().get("A").add("e2"))
			.isInstanceOf(UnsupportedOperationException.class);
		for (String topic : List.of("smoking", "religion", "children", "identity")) {
			assertThatThrownBy(() -> new HistoricalEvaluationEvidence.SourceEvidence("e1", "BOTH",
				"DIFFERENCE", topic, "not approved", "source")).isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> new HistoricalEvaluationEvidence.Snapshot(22, UUID.randomUUID(), "invalid"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new HistoricalEvaluationEvidence.SourceEvidence("e1", "BOTH",
			"ONE_WAY_PREFERENCE_FIT", "contact", "invalid direction", "source"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private MatchProposal matching() {
		MatchProposal matching = MatchProposal.proposed(11, 22, HistoricalEvidenceFixture.MET);
		ReflectionTestUtils.setField(matching, "id", 7L);
		ReflectionTestUtils.setField(matching, "metAt", HistoricalEvidenceFixture.MET);
		ReflectionTestUtils.setField(matching, "policyVersion", "policy-test-1");
		ReflectionTestUtils.setField(matching, "evaluation", Map.of("decision", "SYNTHETIC_CONFIRMED"));
		return matching;
	}

	private HistoricalEvaluationStore store() {
		return new HistoricalEvaluationStore(proposals, mapper, (row, value) -> true, entityManager);
	}

	private Map<String, Object> stored(HistoricalEvaluationEvidence evidence) {
		return Map.of("decision", "SYNTHETIC_CONFIRMED", "historicalEvidence", payload(evidence));
	}

	private Map<String, Object> payload(HistoricalEvaluationEvidence evidence) {
		return mapper.convertValue(evidence, new TypeReference<LinkedHashMap<String, Object>>() { });
	}
}
