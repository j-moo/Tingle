package com.e106.tingle.encounter;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import com.e106.tingle.encounter.MatchingEvaluationRuntimeVerifier.Verification;
import com.e106.tingle.encounter.MatchingEvaluationTypes.Evaluation;
import com.e106.tingle.encounter.MatchingEvaluationTypes.Request;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence.Snapshot;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Explicit-construction adapter only: an official runtime verifier is required before registration. */
public final class ValidatingEncounterEvaluationGateway implements EncounterEvaluationGateway {
	private static final TypeReference<Map<String, Object>> EVALUATION_MAP = new TypeReference<>() { };
	private static final TypeReference<Map<String, String>> MANIFEST_MAP = new TypeReference<>() { };
	private final EncounterReceipts receipts;
	private final StoredEncounterInputLookup inputs;
	private final MatchingEvaluationTransport transport;
	private final MatchingEvaluationRuntimeVerifier runtime;
	private final JsonMapper mapper;

	public ValidatingEncounterEvaluationGateway(EncounterReceipts receipts, StoredEncounterInputLookup inputs,
		MatchingEvaluationTransport transport, MatchingEvaluationRuntimeVerifier runtime, JsonMapper mapper) {
		this.receipts = Objects.requireNonNull(receipts);
		this.inputs = Objects.requireNonNull(inputs);
		this.transport = Objects.requireNonNull(transport);
		this.runtime = Objects.requireNonNull(runtime);
		this.mapper = Objects.requireNonNull(mapper);
	}

	@Override
	public VerifiedResult evaluate(Input input, String inputHash) {
		if (input == null || inputHash == null || !inputHash.matches("[a-f0-9]{64}")
			|| !inputHash.equals(receipts.canonicalHash(input))) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		if (input.generationId() == null || input.metAt() == null || input.left() == null || input.right() == null
			|| input.leftUserId() <= 0 || input.rightUserId() <= input.leftUserId()
			|| input.left().userId() != input.leftUserId() || input.right().userId() != input.rightUserId()
			|| input.policyVersion() == null || input.matchingRuleVersion() == null) {
			throw unresolved();
		}
		Snapshot left = inputs.find(input.left(), input.metAt()).orElseThrow(
			ValidatingEncounterEvaluationGateway::unresolved);
		Snapshot right = inputs.find(input.right(), input.metAt()).orElseThrow(
			ValidatingEncounterEvaluationGateway::unresolved);
		if (!matches(left, input.left()) || !matches(right, input.right())) {
			throw unresolved();
		}
		runtime.requireApprovedRelease(input, left, right);
		Request request = new Request(input.matchingId(), left.snapshotId(), right.snapshotId(), input.releaseId());
		// Stable for every retry of the same frozen generation; worker attempts use a separate lease identity.
		Evaluation received = transport.evaluate(request, input.generationId());
		Evaluation evaluation;
		try {
			// Recheck bindings even for an injected non-HTTP transport implementation.
			evaluation = MatchingEvaluationTypes.decode(mapper.valueToTree(received), request);
		} catch (IllegalArgumentException exception) {
			throw unresolved();
		}
		if (!input.matchingRuleVersion().equals(evaluation.manifest().matchingVersion())) {
			throw unresolved();
		}
		Verification verification = runtime.verify(input, inputHash, left, right, evaluation);
		validateEvidence(input, left, right, evaluation, verification);
		Map<String, Object> decision = mapper.convertValue(evaluation, EVALUATION_MAP);
		return new VerifiedResult(input.generationId(), inputHash, verification.outcome(),
			Collections.unmodifiableMap(decision), verification.evidence());
	}

	private void validateEvidence(Input input, Snapshot left, Snapshot right, Evaluation evaluation,
		Verification verification) {
		if (verification == null || verification.outcome() == null || verification.evidence() == null) {
			throw unresolved();
		}
		HistoricalEvaluationEvidence evidence = verification.evidence();
		Map<String, String> manifest = mapper.convertValue(evaluation.manifest(), MANIFEST_MAP);
		if (evidence.matchingId() != input.matchingId() || !evidence.metAt().equals(input.metAt())
			|| !evidence.policyVersion().equals(input.policyVersion()) || !evidence.left().equals(left)
			|| !evidence.right().equals(right) || !evidence.manifest().equals(manifest)) {
			throw unresolved();
		}
		// These are wire decision contradictions, not locally chosen scoring thresholds.
		if ((verification.outcome() == Outcome.QUALIFIED) != evaluation.eligible()
			|| (verification.outcome() == Outcome.QUALIFIED
				&& (!evaluation.excluded().isEmpty() || !evaluation.unresolved().isEmpty()
					|| evaluation.directionAB() == null || evaluation.directionBA() == null
					|| evaluation.harmonicScore() == null))
			|| (!evaluation.unresolved().isEmpty() && verification.outcome() != Outcome.UNRESOLVED)) {
			throw unresolved();
		}
	}

	private static boolean matches(Snapshot snapshot, ParticipantInput participant) {
		return snapshot.userId() == participant.userId()
			&& snapshot.snapshotId().equals(participant.settingsSnapshotId())
			&& snapshot.inputHash().equals(participant.inputHash())
			&& Objects.equals(snapshot.settingsVersion(), participant.settingsVersion())
			&& Snapshot.sourceRef(participant.userId(), participant.settingsVersion())
				.equals(snapshot.inputSourceRef());
	}

	private static BusinessException unresolved() {
		return new BusinessException(ErrorCode.POLICY_UNRESOLVED);
	}
}
