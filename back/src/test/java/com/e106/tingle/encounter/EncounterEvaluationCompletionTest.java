package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import com.e106.tingle.encounter.EncounterEvaluationGateway.Input;
import com.e106.tingle.encounter.EncounterEvaluationGateway.Outcome;
import com.e106.tingle.encounter.EncounterEvaluationGateway.ParticipantInput;
import com.e106.tingle.encounter.EncounterEvaluationGateway.VerifiedResult;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence.Snapshot;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationStore;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.json.JsonMapper;

/** 합성 근거로 순서와 입력 경계만 검사한다. 실제 DB 원자성·공식 증명 검증의 근거가 아니다. */
@ExtendWith(MockitoExtension.class)
class EncounterEvaluationCompletionTest {

	private static final Instant MET_AT = Instant.parse("2026-10-29T04:00:00Z");
	private static final String INPUT_HASH = "c".repeat(64);
	private static final Map<String, Object> DECISION = Map.of("matched", true);
	private static final String UPDATE = "update matching set evaluation=cast(? as jsonb) "
		+ "where id=? and (evaluation is null or evaluation='{}'::jsonb)";
	private static final String SELECT = "select evaluation::text from matching where id=?";

	@Mock
	private JdbcTemplate jdbc;
	@Mock
	private HistoricalEvaluationStore historical;
	@Mock
	private EntityManager entityManager;
	@Mock
	private StoredEncounterInputLookup inputs;

	private final JsonMapper mapper = JsonMapper.builder().build();
	private EncounterEvaluationCompletion completion;
	private Input input;
	private HistoricalEvaluationEvidence evidence;

	@BeforeEach
	void setUp() {
		completion = new EncounterEvaluationCompletion(jdbc, mapper, historical, entityManager, inputs);
		input = new Input(17, uuid(100), 11, 22, MET_AT, MET_AT.plusSeconds(60),
			MET_AT.plusSeconds(120), "v1", uuid(700), "matching-1", participant(11, 31), participant(22, 32));
		evidence = evidence(snapshot(input.left()), snapshot(input.right()), manifest());
	}

	@Test
	void missingHistoricalEvidenceCannotWriteADecision() {
		assertRejected(new VerifiedResult(input.generationId(), INPUT_HASH, Outcome.QUALIFIED, DECISION),
			ErrorCode.POLICY_UNRESOLVED);
	}

	@ParameterizedTest
	@ValueSource(strings = {"snapshotId", "settingsVersion", "inputHash", "inputSourceRef"})
	void snapshotMustMatchTheFrozenParticipantInput(String field) {
		Snapshot left = snapshot(input.left());
		Snapshot changed = new Snapshot(left.userId(), "snapshotId".equals(field) ? uuid(999) : left.snapshotId(),
			"inputHash".equals(field) ? "b".repeat(64) : left.inputHash(),
			"settingsVersion".equals(field) ? 2L : left.settingsVersion(),
			"inputSourceRef".equals(field) ? "settings_snapshot:11:99" : left.inputSourceRef());
		assertRejected(result(evidence(changed, evidence.right(), evidence.manifest())),
			ErrorCode.IDEMPOTENCY_CONFLICT);
	}

	@ParameterizedTest
	@ValueSource(strings = {"matchingId", "metAt", "policyVersion", "partyOrder"})
	void encounterIdentityAndOriginalPartyOrderMustMatch(String field) {
		HistoricalEvaluationEvidence changed = new HistoricalEvaluationEvidence(1,
			"matchingId".equals(field) ? 18 : input.matchingId(),
			"metAt".equals(field) ? MET_AT.plusSeconds(1) : MET_AT,
			"policyVersion".equals(field) ? "v2" : input.policyVersion(),
			"partyOrder".equals(field) ? evidence.right() : evidence.left(),
			"partyOrder".equals(field) ? evidence.left() : evidence.right(), evidence.manifest(),
			evidence.scopeVersion(), evidence.sourceEvidence(), evidence.displayEvidenceIdsByViewer(),
			evidence.conflictingSourceRefsByViewer());
		assertRejected(result(changed), ErrorCode.IDEMPOTENCY_CONFLICT);
	}

	@ParameterizedTest
	@ValueSource(strings = {"releaseId", "matchingVersion"})
	void manifestMustNameThePinnedReleaseAndMatchingRule(String field) {
		Map<String, String> changed = new LinkedHashMap<>(evidence.manifest());
		changed.put(field, "releaseId".equals(field) ? uuid(999).toString() : "matching-2");
		assertRejected(result(evidence(evidence.left(), evidence.right(), changed)),
			ErrorCode.IDEMPOTENCY_CONFLICT);
	}

	@ParameterizedTest
	@ValueSource(strings = {"historicalEvidence", "matchExplanations"})
	void theGatewayCannotPrepopulateReservedEvaluationNamespaces(String field) {
		Map<String, Object> decision = new LinkedHashMap<>(DECISION);
		decision.put(field, Map.of("synthetic", true));
		assertRejected(new VerifiedResult(input.generationId(), INPUT_HASH, Outcome.QUALIFIED, decision, evidence),
			ErrorCode.POLICY_UNRESOLVED);
	}

	@Test
	void anExistingEvaluationCannotBeOverwritten() {
		resolveBothInputs();
		when(jdbc.update(UPDATE, mapper.writeValueAsString(DECISION), input.matchingId())).thenReturn(0);

		assertThatThrownBy(() -> completion.complete(input, result(evidence)))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
		verify(jdbc).update(UPDATE, mapper.writeValueAsString(DECISION), input.matchingId());
		verifyNoMoreInteractions(jdbc);
		verifyNoInteractions(historical, entityManager);
	}

	@Test
	void decisionThenHistoricalStoreAndFlushPrecedeReadingTheFinalEnvelopeJson() {
		resolveBothInputs();
		MatchProposal captured = mock(MatchProposal.class);
		when(entityManager.getReference(MatchProposal.class, input.matchingId())).thenReturn(captured);
		when(jdbc.update(UPDATE, mapper.writeValueAsString(DECISION), input.matchingId())).thenReturn(1);
		when(jdbc.queryForObject(SELECT, String.class, input.matchingId()))
			.thenReturn("{\"matched\":true,\"historicalEvidence\":{\"schemaVersion\":1}}");

		Map<String, Object> finalized = completion.complete(input, result(evidence));

		assertThat(finalized).containsEntry("matched", true).containsKey("historicalEvidence");
		InOrder ordered = inOrder(inputs, jdbc, historical, entityManager);
		ordered.verify(inputs).find(input.left(), MET_AT);
		ordered.verify(inputs).find(input.right(), MET_AT);
		ordered.verify(jdbc).update(UPDATE, mapper.writeValueAsString(DECISION), input.matchingId());
		ordered.verify(historical).store(evidence);
		ordered.verify(entityManager).flush();
		ordered.verify(entityManager).getReference(MatchProposal.class, input.matchingId());
		ordered.verify(entityManager).detach(captured);
		ordered.verify(jdbc).queryForObject(SELECT, String.class, input.matchingId());
		ordered.verifyNoMoreInteractions();
	}

	@Test
	void flushFailurePropagatesBeforeAnyFinalJsonIsReturned() {
		resolveBothInputs();
		IllegalStateException failure = new IllegalStateException("synthetic flush failure");
		when(jdbc.update(UPDATE, mapper.writeValueAsString(DECISION), input.matchingId())).thenReturn(1);
		doThrow(failure).when(entityManager).flush();

		assertThatThrownBy(() -> completion.complete(input, result(evidence))).isSameAs(failure);
		InOrder ordered = inOrder(jdbc, historical, entityManager);
		ordered.verify(jdbc).update(UPDATE, mapper.writeValueAsString(DECISION), input.matchingId());
		ordered.verify(historical).store(evidence);
		ordered.verify(entityManager).flush();
		ordered.verifyNoMoreInteractions();
	}

	@Test
	void historicalStoreFailureIsNotSwallowedOrFlushed() {
		resolveBothInputs();
		BusinessException failure = new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		when(jdbc.update(UPDATE, mapper.writeValueAsString(DECISION), input.matchingId())).thenReturn(1);
		when(historical.store(evidence)).thenThrow(failure);

		assertThatThrownBy(() -> completion.complete(input, result(evidence))).isSameAs(failure);
		verify(jdbc).update(UPDATE, mapper.writeValueAsString(DECISION), input.matchingId());
		verifyNoMoreInteractions(jdbc);
		verifyNoInteractions(entityManager);
	}

	@ParameterizedTest
	@ValueSource(strings = {"left", "right"})
	void missingStoredInputOnEitherSideCannotWriteADecision(String side) {
		if ("right".equals(side)) {
			when(inputs.find(input.left(), MET_AT)).thenReturn(Optional.of(evidence.left()));
		}
		ParticipantInput missing = "left".equals(side) ? input.left() : input.right();
		when(inputs.find(missing, MET_AT)).thenReturn(Optional.empty());

		assertRejected(result(evidence), ErrorCode.POLICY_UNRESOLVED);
		verify(inputs).find(missing, MET_AT);
		if ("left".equals(side)) {
			verify(inputs, never()).find(input.right(), MET_AT);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"left", "right"})
	void storedSnapshotMustMatchTheReturnedHistoricalEvidence(String side) {
		if ("right".equals(side)) {
			when(inputs.find(input.left(), MET_AT)).thenReturn(Optional.of(evidence.left()));
		}
		ParticipantInput changed = "left".equals(side) ? input.left() : input.right();
		Snapshot different = new Snapshot(changed.userId(), uuid(999), changed.inputHash(),
			changed.settingsVersion(), Snapshot.sourceRef(changed.userId(), changed.settingsVersion()));
		when(inputs.find(changed, MET_AT)).thenReturn(Optional.of(different));

		assertRejected(result(evidence), ErrorCode.POLICY_UNRESOLVED);
	}

	private void resolveBothInputs() {
		when(inputs.find(input.left(), MET_AT)).thenReturn(Optional.of(evidence.left()));
		when(inputs.find(input.right(), MET_AT)).thenReturn(Optional.of(evidence.right()));
	}

	private void assertRejected(VerifiedResult result, ErrorCode errorCode) {
		assertThatThrownBy(() -> completion.complete(input, result))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
		verifyNoInteractions(jdbc, historical, entityManager);
	}

	private VerifiedResult result(HistoricalEvaluationEvidence captured) {
		return new VerifiedResult(input.generationId(), INPUT_HASH, Outcome.QUALIFIED, DECISION, captured);
	}

	private HistoricalEvaluationEvidence evidence(Snapshot left, Snapshot right, Map<String, String> manifest) {
		Map<String, List<String>> emptyViewers = Map.of("A", List.of(), "B", List.of());
		return new HistoricalEvaluationEvidence(1, input.matchingId(), input.metAt(), input.policyVersion(),
			left, right, manifest, "synthetic-scope-1", List.of(), emptyViewers, emptyViewers);
	}

	private Map<String, String> manifest() {
		Map<String, String> values = new LinkedHashMap<>();
		for (String field : List.of("releaseId", "surveyVersion", "wordingVersion", "scoringVersion",
			"typeModelVersion", "analysisVersion", "typeReferenceVersion", "matchingVersion",
			"productPolicyVersion", "engineVersion", "contentHash")) {
			values.put(field, "synthetic-1");
		}
		values.put("releaseId", input.releaseId().toString());
		values.put("matchingVersion", input.matchingRuleVersion());
		values.put("contentHash", "f".repeat(64));
		return values;
	}

	private static Snapshot snapshot(ParticipantInput participant) {
		return new Snapshot(participant.userId(), participant.settingsSnapshotId(), participant.inputHash(),
			participant.settingsVersion(), Snapshot.sourceRef(participant.userId(), participant.settingsVersion()));
	}

	private static ParticipantInput participant(long userId, long deviceId) {
		return new ParticipantInput(userId, deviceId, uuid(userId + 100), uuid(userId + 200), INPUT_HASH,
			uuid(userId + 300), 1, uuid(userId + 400), (userId == 11 ? "a" : "b").repeat(64), "d".repeat(64));
	}

	private static UUID uuid(long value) {
		return new UUID(0, value);
	}
}
