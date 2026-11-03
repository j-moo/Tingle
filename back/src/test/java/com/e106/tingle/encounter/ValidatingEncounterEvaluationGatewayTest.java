package com.e106.tingle.encounter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import com.e106.tingle.encounter.EncounterEvaluationGateway.Input;
import com.e106.tingle.encounter.EncounterEvaluationGateway.Outcome;
import com.e106.tingle.encounter.EncounterEvaluationGateway.ParticipantInput;
import com.e106.tingle.encounter.MatchingEvaluationRuntimeVerifier.Verification;
import com.e106.tingle.encounter.MatchingEvaluationTypes.Evaluation;
import com.e106.tingle.encounter.MatchingEvaluationTypes.Request;
import com.e106.tingle.global.ai.AiCallException;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence.Snapshot;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic local adapter tests; the verifier stub does not establish official runtime approval or evidence. */
class ValidatingEncounterEvaluationGatewayTest {
	private static final Instant MET_AT = Instant.parse("2026-11-02T01:00:00Z");
	private static final TypeReference<Map<String, String>> MANIFEST_MAP = new TypeReference<>() { };
	private final JsonMapper mapper = MatchingEvaluationFixture.JSON;
	private final StoredEncounterInputLookup inputs = mock(StoredEncounterInputLookup.class);
	private final MatchingEvaluationTransport transport = mock(MatchingEvaluationTransport.class);
	private final MatchingEvaluationRuntimeVerifier runtime = mock(MatchingEvaluationRuntimeVerifier.class);
	private final EncounterReceipts receipts = new EncounterReceipts(mock(JdbcTemplate.class), mapper);
	private final ValidatingEncounterEvaluationGateway gateway = new ValidatingEncounterEvaluationGateway(
		receipts, inputs, transport, runtime, mapper);
	private Input input;
	private String hash;
	private Snapshot left;
	private Snapshot right;
	private Evaluation evaluation;
	private HistoricalEvaluationEvidence evidence;

	@BeforeEach
	void setUp() {
		Request request = MatchingEvaluationFixture.REQUEST;
		input = new Input(request.encounterId(), uuid(99), 11, 22, MET_AT, MET_AT.plusSeconds(60),
			MET_AT.plusSeconds(120), "synthetic-policy", request.releaseId(), "synthetic-matching",
			participant(11, request.leftSnapshotId()), participant(22, request.rightSnapshotId()));
		hash = receipts.canonicalHash(input);
		left = snapshot(input.left());
		right = snapshot(input.right());
		evaluation = MatchingEvaluationTypes.decode(MatchingEvaluationFixture.response(), request);
		evidence = evidence(left, right, manifest(), input.matchingId(), MET_AT, input.policyVersion());
		when(inputs.find(input.left(), MET_AT)).thenReturn(Optional.of(left));
		when(inputs.find(input.right(), MET_AT)).thenReturn(Optional.of(right));
		when(transport.evaluate(request, input.generationId())).thenReturn(evaluation);
		when(runtime.verify(input, hash, left, right, evaluation))
			.thenReturn(new Verification(Outcome.QUALIFIED, evidence));
	}

	@Test
	void validatesStoredInputsAndApprovalBeforeTransportThenBindsOfficialEvidence() {
		var result = gateway.evaluate(input, hash);
		assertThat(result.generationId()).isEqualTo(input.generationId());
		assertThat(result.inputHash()).isEqualTo(hash);
		assertThat(result.outcome()).isEqualTo(Outcome.QUALIFIED);
		assertThat(result.historicalEvidence()).isSameAs(evidence);
		assertThat(result.evaluation()).hasSize(13).containsEntry("eligible", true)
			.doesNotContainKeys("historicalEvidence", "matchExplanations");
		InOrder order = inOrder(inputs, runtime, transport);
		order.verify(inputs).find(input.left(), MET_AT);
		order.verify(inputs).find(input.right(), MET_AT);
		order.verify(runtime).requireApprovedRelease(input, left, right);
		order.verify(transport).evaluate(MatchingEvaluationFixture.REQUEST, input.generationId());
		order.verify(runtime).verify(input, hash, left, right, evaluation);
	}

	@Test
	void retriesKeepTheSameGenerationIdempotencyKey() {
		gateway.evaluate(input, hash);
		gateway.evaluate(input, hash);
		verify(transport, times(2)).evaluate(MatchingEvaluationFixture.REQUEST, input.generationId());
	}

	@Test
	void changedCanonicalInputHashStopsBeforeAnySupplierOrNetworkCall() {
		rejected(() -> gateway.evaluate(input, "f".repeat(64)), ErrorCode.IDEMPOTENCY_CONFLICT);
		verifyNoInteractions(inputs, runtime, transport);
	}

	@ParameterizedTest
	@ValueSource(strings = {"left", "right"})
	void eitherMissingStoredInputStopsBeforeApprovalAndTransport(String missing) {
		when(inputs.find("left".equals(missing) ? input.left() : input.right(), MET_AT)).thenReturn(Optional.empty());
		rejected(() -> gateway.evaluate(input, hash), ErrorCode.POLICY_UNRESOLVED);
		verifyNoInteractions(runtime, transport);
	}

	@Test
	void storedSnapshotMustStillCorrespondToFrozenParticipant() {
		when(inputs.find(input.left(), MET_AT)).thenReturn(Optional.of(new Snapshot(11, left.snapshotId(),
			"b".repeat(64), left.settingsVersion(), left.inputSourceRef())));
		rejected(() -> gateway.evaluate(input, hash), ErrorCode.POLICY_UNRESOLVED);
		verifyNoInteractions(runtime, transport);
	}

	@Test
	void unapprovedReleaseCannotReachTransport() {
		doThrow(new BusinessException(ErrorCode.POLICY_UNRESOLVED)).when(runtime)
			.requireApprovedRelease(input, left, right);
		rejected(() -> gateway.evaluate(input, hash), ErrorCode.POLICY_UNRESOLVED);
		verifyNoInteractions(transport);
	}

	@Test
	void preservesTransportRetryClassification() {
		AiCallException failure = new AiCallException(true);
		when(transport.evaluate(any(), any())).thenThrow(failure);
		assertThatThrownBy(() -> gateway.evaluate(input, hash)).isSameAs(failure);
	}

	@ParameterizedTest
	@ValueSource(strings = {"encounterId", "leftSnapshotId", "rightSnapshotId", "releaseId", "matchingVersion"})
	void rechecksTransportBindingsEvenWithoutHttpDecoder(String field) {
		ObjectNode changed = MatchingEvaluationFixture.response();
		if ("encounterId".equals(field)) {
			changed.put(field, 999);
		} else if ("releaseId".equals(field) || "matchingVersion".equals(field)) {
			((ObjectNode)changed.get("manifest")).put(field,
				"releaseId".equals(field) ? uuid(999).toString() : "other-rule");
		} else {
			changed.put(field, uuid(999).toString());
		}
		Evaluation mismatched = mapper.treeToValue(changed, Evaluation.class);
		when(transport.evaluate(any(), any())).thenReturn(mismatched);
		rejected(() -> gateway.evaluate(input, hash), ErrorCode.POLICY_UNRESOLVED);
	}

	@ParameterizedTest
	@ValueSource(strings = {"matchingId", "metAt", "policyVersion", "partyOrder", "snapshot", "manifest"})
	void refusesHistoricalEvidenceBoundToAnotherExecution(String field) {
		Map<String, String> versions = new LinkedHashMap<>(manifest());
		if ("manifest".equals(field)) {
			versions.put("engineVersion", "other-engine");
		}
		Snapshot altered = new Snapshot(left.userId(), left.snapshotId(), "b".repeat(64),
			left.settingsVersion(), left.inputSourceRef());
		HistoricalEvaluationEvidence changed = evidence(
			"partyOrder".equals(field) ? right : "snapshot".equals(field) ? altered : left,
			"partyOrder".equals(field) ? left : right, versions,
			"matchingId".equals(field) ? 999 : input.matchingId(),
			"metAt".equals(field) ? MET_AT.plusSeconds(1) : MET_AT,
			"policyVersion".equals(field) ? "other-policy" : input.policyVersion());
		when(runtime.verify(input, hash, left, right, evaluation))
			.thenReturn(new Verification(Outcome.QUALIFIED, changed));
		rejected(() -> gateway.evaluate(input, hash), ErrorCode.POLICY_UNRESOLVED);
	}

	@ParameterizedTest
	@ValueSource(strings = {"verification", "outcome", "evidence", "contradictoryOutcome"})
	void runtimeVerificationCannotBeMissingOrContradictTheWireDecision(String missing) {
		Verification result = switch (missing) {
			case "verification" -> null;
			case "outcome" -> new Verification(null, evidence);
			case "evidence" -> new Verification(Outcome.QUALIFIED, null);
			default -> new Verification(Outcome.NOT_MATCHED, evidence);
		};
		when(runtime.verify(input, hash, left, right, evaluation)).thenReturn(result);
		rejected(() -> gateway.evaluate(input, hash), ErrorCode.POLICY_UNRESOLVED);
	}

	@ParameterizedTest
	@ValueSource(strings = {"excluded", "unresolved", "directionAB", "directionBA", "harmonicScore"})
	void qualifiedOutcomeCannotBypassWireRestrictionsOrMissingScores(String field) {
		ObjectNode response = MatchingEvaluationFixture.response();
		if ("excluded".equals(field) || "unresolved".equals(field)) {
			response.putArray(field).add("synthetic-restriction");
		} else {
			response.putNull(field);
		}
		Evaluation restricted = MatchingEvaluationTypes.decode(response, MatchingEvaluationFixture.REQUEST);
		when(transport.evaluate(any(), any())).thenReturn(restricted);
		when(runtime.verify(input, hash, left, right, restricted))
			.thenReturn(new Verification(Outcome.QUALIFIED, evidence));
		rejected(() -> gateway.evaluate(input, hash), ErrorCode.POLICY_UNRESOLVED);
	}

	@Test
	void nullableScoresRemainNullForAnOfficialInsufficientDecision() {
		ObjectNode response = MatchingEvaluationFixture.response();
		response.put("eligible", false);
		response.putNull("directionAB");
		response.putNull("harmonicScore");
		Evaluation insufficient = MatchingEvaluationTypes.decode(response, MatchingEvaluationFixture.REQUEST);
		when(transport.evaluate(any(), any())).thenReturn(insufficient);
		when(runtime.verify(input, hash, left, right, insufficient))
			.thenReturn(new Verification(Outcome.INSUFFICIENT, evidence));
		var result = gateway.evaluate(input, hash);
		assertThat(result.outcome()).isEqualTo(Outcome.INSUFFICIENT);
		assertThat(result.evaluation()).containsEntry("directionAB", null).containsEntry("harmonicScore", null);
	}

	private Map<String, String> manifest() {
		return mapper.convertValue(evaluation.manifest(), MANIFEST_MAP);
	}

	private static HistoricalEvaluationEvidence evidence(Snapshot left, Snapshot right, Map<String, String> manifest,
		long matchingId, Instant metAt, String policy) {
		return new HistoricalEvaluationEvidence(1, matchingId, metAt, policy, left, right, manifest, "synthetic-scope",
			List.of(), Map.of("A", List.of(), "B", List.of()), Map.of("A", List.of(), "B", List.of()));
	}

	private static ParticipantInput participant(long userId, UUID snapshotId) {
		return new ParticipantInput(userId, userId + 100, uuid(userId + 200), uuid(userId + 300), "a".repeat(64),
			snapshotId, 1, uuid(userId + 400), "c".repeat(64), "d".repeat(64));
	}

	private static Snapshot snapshot(ParticipantInput participant) {
		return new Snapshot(participant.userId(), participant.settingsSnapshotId(), participant.inputHash(),
			participant.settingsVersion(), Snapshot.sourceRef(participant.userId(), participant.settingsVersion()));
	}

	private static UUID uuid(long value) {
		return new UUID(0, value);
	}

	private static void rejected(Runnable action, ErrorCode code) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
	}
}
