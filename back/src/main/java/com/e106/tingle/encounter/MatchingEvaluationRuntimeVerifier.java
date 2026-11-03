package com.e106.tingle.encounter;

import com.e106.tingle.encounter.EncounterEvaluationGateway.Input;
import com.e106.tingle.encounter.EncounterEvaluationGateway.Outcome;
import com.e106.tingle.encounter.MatchingEvaluationTypes.Evaluation;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence.Snapshot;

/**
 * Official release/producer and historical decision trust boundary. A shape-valid HTTP response is not proof.
 * Implementations must validate the approved immutable release, actual producer, original A/B inputs,
 * official outcome and directional source/public-scope evidence. Do not infer missing facts or thresholds.
 * Verification must use supplied immutable evidence locally, without another network call.
 * No default implementation or production bean is supplied while the official runtime is unavailable.
 */
public interface MatchingEvaluationRuntimeVerifier {
	void requireApprovedRelease(Input input, Snapshot left, Snapshot right);

	Verification verify(Input input, String inputHash, Snapshot left, Snapshot right, Evaluation evaluation);

	record Verification(Outcome outcome, HistoricalEvaluationEvidence evidence) {
	}
}
