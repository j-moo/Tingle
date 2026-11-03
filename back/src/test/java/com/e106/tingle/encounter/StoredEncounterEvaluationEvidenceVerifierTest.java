package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

import com.e106.tingle.encounter.EncounterEvaluationGateway.Input;
import com.e106.tingle.encounter.EncounterEvaluationGateway.Outcome;
import com.e106.tingle.encounter.EncounterEvaluationGateway.ParticipantInput;
import com.e106.tingle.encounter.EncounterEvaluationWorker.State;
import com.e106.tingle.encounter.EncounterEvaluationWorker.Work;
import com.e106.tingle.encounter.MatchingEvaluationRuntimeVerifier.Verification;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence.Snapshot;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class StoredEncounterEvaluationEvidenceVerifierTest {
	private static final Instant MET_AT = Instant.parse("2026-11-02T03:00:00Z");
	private static final String HASH = "a".repeat(64);
	private static final String SQL = "select request_state->>cast(? as text) from matching_participant "
		+ "where matching_id=? and user_id=?";
	private final JsonMapper mapper = JsonMapper.builder().build();
	private JdbcTemplate jdbc;
	private StoredEncounterInputLookup inputs;
	private MatchingEvaluationRuntimeVerifier runtime;
	private StoredEncounterEvaluationEvidenceVerifier verifier;
	private MatchProposal matching;
	private EncounterReceipts receipts;
	private Input input;
	private Snapshot left;
	private Snapshot right;
	private HistoricalEvaluationEvidence evidence;
	private Map<String, Object> decision;

	@BeforeEach
	void prepareStoredDecision() {
		jdbc = mock(JdbcTemplate.class);
		inputs = mock(StoredEncounterInputLookup.class);
		runtime = mock(MatchingEvaluationRuntimeVerifier.class);
		matching = mock(MatchProposal.class);
		receipts = new EncounterReceipts(jdbc, mapper);
		var request = MatchingEvaluationFixture.REQUEST;
		ParticipantInput first = participant(11, 101, request.leftSnapshotId());
		ParticipantInput second = participant(22, 102, request.rightSnapshotId());
		input = new Input(request.encounterId(), UUID.randomUUID(), 11, 22, MET_AT,
			MET_AT.plusSeconds(60), MET_AT.plusSeconds(30), "synthetic-policy", request.releaseId(),
			"synthetic-matching", first, second);
		left = snapshot(first);
		right = snapshot(second);
		Map<String, String> manifest = mapper.convertValue(MatchingEvaluationFixture.response().get("manifest"),
			new TypeReference<LinkedHashMap<String, String>>() { });
		evidence = evidence(manifest, "synthetic-scope");
		decision = mapper.convertValue(MatchingEvaluationFixture.response(),
			new TypeReference<LinkedHashMap<String, Object>>() { });
		given(matching.getId()).willReturn(request.encounterId());
		given(matching.getUserLow()).willReturn(11L);
		given(matching.getUserHigh()).willReturn(22L);
		given(matching.getMetAt()).willReturn(MET_AT);
		given(matching.getPolicyVersion()).willReturn(input.policyVersion());
		given(matching.getEvaluation()).willReturn(decision);
		given(inputs.find(first, MET_AT)).willReturn(Optional.of(left));
		given(inputs.find(second, MET_AT)).willReturn(Optional.of(right));
		given(runtime.verify(eq(input), eq(receipts.canonicalHash(input)), eq(left), eq(right), any()))
			.willReturn(new Verification(Outcome.QUALIFIED, evidence));
		verifier = new StoredEncounterEvaluationEvidenceVerifier(jdbc, mapper, receipts, inputs, runtime);
		saved(work(State.RUNNING));
	}

	@ParameterizedTest
	@EnumSource(value = State.class, names = {"RUNNING", "SUCCEEDED"})
	void verifiesOnlyStoredInputsAndRuntimeApprovedEvidence(State state) {
		saved(work(state));
		decision.put("historicalEvidence", mapper.valueToTree(evidence));
		decision.put("matchExplanations", Map.of("existing", "preserved"));
		assertThat(verifier.verify(matching, evidence)).isTrue();
		then(runtime).should().requireApprovedRelease(input, left, right);
		then(runtime).should().verify(eq(input), eq(receipts.canonicalHash(input)), eq(left), eq(right), any());
		assertThat(decision).containsKeys("historicalEvidence", "matchExplanations");
	}

	@ParameterizedTest
	@EnumSource(value = State.class, names = {"RUNNING", "SUCCEEDED"}, mode = EnumSource.Mode.EXCLUDE)
	void rejectsUnfinishedOrFailedWorkflowStates(State state) {
		saved(work(state));
		assertThat(verifier.verify(matching, evidence)).isFalse();
		then(runtime).shouldHaveNoInteractions();
	}

	@Test
	void rejectsMissingMalformedOrAmbiguousStoredWorkflow() {
		for (List<String> rows : List.of(List.<String>of(), List.of("not-json"), List.of("{}", "{}"))) {
			given(jdbc.queryForList(SQL, String.class, EncounterEvaluationWorker.WORKFLOW_KEY, 1001L, 11L))
				.willReturn(rows);
			assertThat(verifier.verify(matching, evidence)).isFalse();
		}
		then(runtime).shouldHaveNoInteractions();
	}

	@Test
	void rejectsWorkflowWithoutAnActualAttemptOrRunningLease() {
		Work original = work(State.RUNNING);
		for (Work changed : List.of(
			new Work(original.generationId(), null, original.inputHash(), State.RUNNING, 1,
				original.leaseUntil(), null, input),
			new Work(original.generationId(), original.attemptId(), original.inputHash(), State.SUCCEEDED, 0,
				null, null, input),
			new Work(original.generationId(), original.attemptId(), original.inputHash(), State.RUNNING, 1,
				null, null, input))) {
			saved(changed);
			assertThat(verifier.verify(matching, evidence)).isFalse();
		}
		then(runtime).shouldHaveNoInteractions();
	}

	@Test
	void rejectsChangedGenerationOrCanonicalInputHash() {
		Work original = work(State.RUNNING);
		for (Work changed : List.of(new Work(UUID.randomUUID(), original.attemptId(), original.inputHash(),
			State.RUNNING, 1, original.leaseUntil(), null, input),
			new Work(input.generationId(), original.attemptId(), "b".repeat(64), State.RUNNING, 1,
				original.leaseUntil(), null, input))) {
			saved(changed);
			assertThat(verifier.verify(matching, evidence)).isFalse();
		}
		then(runtime).shouldHaveNoInteractions();
	}

	@Test
	void originalLeftAndRightArePreservedInsteadOfSortingTheDecision() {
		Input original = input;
		input = new Input(original.matchingId(), original.generationId(), 22, 11, MET_AT,
			original.opportunityExpiresAt(), original.counterpartDeadlineAt(), original.policyVersion(),
			original.releaseId(), original.matchingRuleVersion(), original.right(), original.left());
		Snapshot before = left;
		left = right;
		right = before;
		evidence = evidence(evidence.manifest(), "synthetic-scope");
		decision.put("leftSnapshotId", left.snapshotId().toString());
		decision.put("rightSnapshotId", right.snapshotId().toString());
		given(runtime.verify(eq(input), eq(receipts.canonicalHash(input)), eq(left), eq(right), any()))
			.willReturn(new Verification(Outcome.QUALIFIED, evidence));
		saved(work(State.SUCCEEDED));
		assertThat(verifier.verify(matching, evidence)).isTrue();
		then(runtime).should().requireApprovedRelease(input, left, right);
	}

	@Test
	void rejectsMatchingIdentityTimePolicyAndParticipantMismatch() {
		given(matching.getId()).willReturn(999L);
		assertThat(verifier.verify(matching, evidence)).isFalse();
		given(matching.getId()).willReturn(1001L);
		given(matching.getMetAt()).willReturn(MET_AT.plusSeconds(1));
		assertThat(verifier.verify(matching, evidence)).isFalse();
		given(matching.getMetAt()).willReturn(MET_AT);
		given(matching.getPolicyVersion()).willReturn("changed-policy");
		assertThat(verifier.verify(matching, evidence)).isFalse();
		given(matching.getPolicyVersion()).willReturn(input.policyVersion());
		given(matching.getUserHigh()).willReturn(33L);
		assertThat(verifier.verify(matching, evidence)).isFalse();
		then(runtime).shouldHaveNoInteractions();
	}

	@Test
	void missingOrReplacedStoredSnapshotCannotBeFilledWithCurrentInputs() {
		given(inputs.find(input.left(), MET_AT)).willReturn(Optional.empty());
		assertThat(verifier.verify(matching, evidence)).isFalse();
		given(inputs.find(input.left(), MET_AT)).willReturn(Optional.of(new Snapshot(11, UUID.randomUUID(), HASH,
			1L, Snapshot.sourceRef(11, 1))));
		assertThat(verifier.verify(matching, evidence)).isFalse();
		then(runtime).shouldHaveNoInteractions();
	}

	@Test
	void rejectsExtraDecisionFieldsAndMismatchedManifest() {
		decision.put("unapproved", true);
		assertThat(verifier.verify(matching, evidence)).isFalse();
		decision.remove("unapproved");
		Map<String, String> manifest = new LinkedHashMap<>(evidence.manifest());
		manifest.put("contentHash", "b".repeat(64));
		decision.put("manifest", manifest);
		assertThat(verifier.verify(matching, evidence)).isFalse();
		then(runtime).shouldHaveNoInteractions();
	}

	@Test
	void rejectsChangedOutcomeAndUnresolvedRuntimeResult() {
		for (Outcome outcome : List.of(Outcome.NOT_MATCHED, Outcome.INSUFFICIENT, Outcome.UNRESOLVED)) {
			given(runtime.verify(eq(input), anyString(), eq(left), eq(right), any()))
				.willReturn(new Verification(outcome, evidence));
			assertThat(verifier.verify(matching, evidence)).isFalse();
		}
		given(runtime.verify(eq(input), anyString(), eq(left), eq(right), any()))
			.willReturn(new Verification(Outcome.QUALIFIED, evidence));
		decision.put("eligible", false);
		assertThat(verifier.verify(matching, evidence)).isFalse();
	}

	@Test
	void qualifiedDecisionRequiresResolvedNonExcludedAndCompleteScores() {
		decision.put("excluded", List.of("synthetic-exclusion"));
		assertThat(verifier.verify(matching, evidence)).isFalse();
		decision.put("excluded", List.of());
		decision.put("unresolved", List.of("synthetic-unresolved"));
		assertThat(verifier.verify(matching, evidence)).isFalse();
		decision.put("unresolved", List.of());
		for (String field : List.of("directionAB", "directionBA", "harmonicScore")) {
			Object previous = decision.put(field, null);
			assertThat(verifier.verify(matching, evidence)).isFalse();
			decision.put(field, previous);
		}
	}

	@Test
	void unresolvedReasonsCannotBeStoredForAnOtherwiseNegativeDecision() {
		decision.put("eligible", false);
		decision.put("unresolved", List.of("missing-policy"));
		given(runtime.verify(eq(input), anyString(), eq(left), eq(right), any()))
			.willReturn(new Verification(Outcome.NOT_MATCHED, evidence));
		assertThat(verifier.verify(matching, evidence)).isFalse();
		decision.put("unresolved", List.of());
		assertThat(verifier.verify(matching, evidence)).isTrue();
	}

	@Test
	void rejectsUnapprovedReleaseAndMissingOrDifferentRuntimeEvidence() {
		willThrow(new IllegalStateException("unavailable official release")).given(runtime)
			.requireApprovedRelease(input, left, right);
		assertThat(verifier.verify(matching, evidence)).isFalse();
		willDoNothing().given(runtime).requireApprovedRelease(input, left, right);
		given(runtime.verify(eq(input), anyString(), eq(left), eq(right), any())).willReturn(null);
		assertThat(verifier.verify(matching, evidence)).isFalse();
		given(runtime.verify(eq(input), anyString(), eq(left), eq(right), any()))
			.willReturn(new Verification(Outcome.QUALIFIED, evidence(evidence.manifest(), "different-scope")));
		assertThat(verifier.verify(matching, evidence)).isFalse();
	}

	private void saved(Work work) {
		given(jdbc.queryForList(SQL, String.class, EncounterEvaluationWorker.WORKFLOW_KEY, 1001L, 11L))
			.willReturn(List.of(mapper.writeValueAsString(work)));
	}

	private Work work(State state) {
		return new Work(input.generationId(), UUID.randomUUID(), receipts.canonicalHash(input), state, 1,
			MET_AT.plusSeconds(60), null, input);
	}

	private HistoricalEvaluationEvidence evidence(Map<String, String> manifest, String scope) {
		return new HistoricalEvaluationEvidence(1, 1001, MET_AT, "synthetic-policy", left, right, manifest, scope,
			List.of(), Map.of("A", List.of(), "B", List.of()), Map.of("A", List.of(), "B", List.of()));
	}

	private static ParticipantInput participant(long userId, long deviceId, UUID snapshotId) {
		return new ParticipantInput(userId, deviceId, UUID.randomUUID(), UUID.randomUUID(), HASH, snapshotId, 1,
			UUID.randomUUID(), HASH, HASH);
	}

	private static Snapshot snapshot(ParticipantInput participant) {
		return new Snapshot(participant.userId(), participant.settingsSnapshotId(), participant.inputHash(),
			participant.settingsVersion(), Snapshot.sourceRef(participant.userId(), participant.settingsVersion()));
	}
}
