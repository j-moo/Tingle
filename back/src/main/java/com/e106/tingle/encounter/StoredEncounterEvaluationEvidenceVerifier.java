package com.e106.tingle.encounter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

import com.e106.tingle.encounter.EncounterEvaluationGateway.Input;
import com.e106.tingle.encounter.EncounterEvaluationGateway.Outcome;
import com.e106.tingle.encounter.EncounterEvaluationWorker.State;
import com.e106.tingle.encounter.EncounterEvaluationWorker.Work;
import com.e106.tingle.encounter.MatchingEvaluationTypes.Evaluation;
import com.e106.tingle.encounter.MatchingEvaluationTypes.Manifest;
import com.e106.tingle.encounter.MatchingEvaluationTypes.Request;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence.Snapshot;
import com.e106.tingle.proposal.evaluation.HistoricalEvidenceVerifier;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicitly wired storage verification adapter. The runtime port must validate official release/producer
 * and decision evidence locally; neither this adapter nor that port may make a network call here.
 * No production bean is registered while an approved runtime verifier is unavailable.
 */
public final class StoredEncounterEvaluationEvidenceVerifier implements HistoricalEvidenceVerifier {
	private final JdbcTemplate jdbc;
	private final JsonMapper mapper;
	private final EncounterReceipts receipts;
	private final StoredEncounterInputLookup inputs;
	private final MatchingEvaluationRuntimeVerifier runtime;

	public StoredEncounterEvaluationEvidenceVerifier(JdbcTemplate jdbc, JsonMapper mapper, EncounterReceipts receipts,
		StoredEncounterInputLookup inputs, MatchingEvaluationRuntimeVerifier runtime) {
		this.jdbc = Objects.requireNonNull(jdbc);
		this.mapper = Objects.requireNonNull(mapper).rebuild()
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
		this.receipts = Objects.requireNonNull(receipts);
		this.inputs = Objects.requireNonNull(inputs);
		this.runtime = Objects.requireNonNull(runtime);
	}

	@Override
	public boolean verify(MatchProposal matching, HistoricalEvaluationEvidence evidence) {
		try {
			return verifyStored(matching, evidence);
		} catch (RuntimeException exception) {
			// Missing, malformed or unverifiable history never falls back to current inputs or invented evidence.
			return false;
		}
	}

	private boolean verifyStored(MatchProposal matching, HistoricalEvaluationEvidence evidence) {
		if (matching == null || evidence == null || matching.getId() == null
			|| matching.getId() != evidence.matchingId() || matching.getEvaluation() == null) {
			return false;
		}
		List<String> saved = jdbc.queryForList("select request_state->>cast(? as text) from matching_participant "
			+ "where matching_id=? and user_id=?", String.class, EncounterEvaluationWorker.WORKFLOW_KEY,
			matching.getId(), matching.getUserLow());
		if (saved.size() != 1 || saved.getFirst() == null) {
			return false;
		}
		Work work = mapper.readValue(saved.getFirst(), Work.class);
		Input input = work.input();
		if (!Set.of(State.RUNNING, State.SUCCEEDED).contains(work.state()) || input == null
			|| work.attemptId() == null || work.attempts() < 1
			|| (work.state() == State.RUNNING && work.leaseUntil() == null)
			|| input.generationId() == null || !input.generationId().equals(work.generationId())
			|| !receipts.canonicalHash(input).equals(work.inputHash()) || !bound(matching, evidence, input)) {
			return false;
		}
		Snapshot left = inputs.find(input.left(), input.metAt()).orElse(null);
		Snapshot right = inputs.find(input.right(), input.metAt()).orElse(null);
		if (!evidence.left().equals(left) || !evidence.right().equals(right)) {
			return false;
		}
		Map<String, Object> decision = new LinkedHashMap<>(matching.getEvaluation());
		decision.remove(HistoricalEvaluationEvidence.STORAGE_KEY);
		decision.remove("matchExplanations");
		Request request = new Request(input.matchingId(), input.left().settingsSnapshotId(),
			input.right().settingsSnapshotId(), input.releaseId());
		Evaluation evaluation = MatchingEvaluationTypes.decode(mapper.valueToTree(decision), request);
		if (!evaluation.manifest().equals(mapper.convertValue(evidence.manifest(), Manifest.class))) {
			return false;
		}
		runtime.requireApprovedRelease(input, left, right);
		MatchingEvaluationRuntimeVerifier.Verification verified = runtime.verify(input, work.inputHash(), left, right,
			evaluation);
		return verified != null && consistent(verified.outcome(), evaluation) && evidence.equals(verified.evidence());
	}

	private static boolean consistent(Outcome outcome, Evaluation evaluation) {
		if (outcome == null || outcome == Outcome.UNRESOLVED || !evaluation.unresolved().isEmpty()
			|| (outcome == Outcome.QUALIFIED) != evaluation.eligible()) {
			return false;
		}
		return outcome != Outcome.QUALIFIED || (evaluation.excluded().isEmpty() && evaluation.directionAB() != null
			&& evaluation.directionBA() != null && evaluation.harmonicScore() != null);
	}

	private static boolean bound(MatchProposal matching, HistoricalEvaluationEvidence evidence, Input input) {
		return input.matchingId() == matching.getId() && input.metAt() != null
			&& input.metAt().equals(matching.getMetAt()) && input.metAt().equals(evidence.metAt())
			&& Objects.equals(input.policyVersion(), matching.getPolicyVersion())
			&& Objects.equals(input.policyVersion(), evidence.policyVersion())
			&& matching.getUserLow() == Math.min(input.leftUserId(), input.rightUserId())
			&& matching.getUserHigh() == Math.max(input.leftUserId(), input.rightUserId())
			&& input.left() != null && input.right() != null
			&& input.left().userId() == input.leftUserId() && input.right().userId() == input.rightUserId()
			&& evidence.left().userId() == input.leftUserId() && evidence.right().userId() == input.rightUserId()
			&& Objects.equals(input.matchingRuleVersion(), evidence.manifest().get("matchingVersion"));
	}
}
