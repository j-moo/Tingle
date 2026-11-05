package com.e106.tingle.proposal.explanation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationStore;
import com.e106.tingle.proposal.evaluation.StoredHistoricalInputLookup;

import tools.jackson.databind.ObjectMapper;

/** 검증된 과거 저장 근거에서 해당 열람자에게 공개된 요약만 직접 입력으로 만든다. */
@Component
public class JpaHistoricalExplanationSource implements HistoricalExplanationSource {

	private static final Set<String> TOPICS = Set.of("contact", "planning", "activity", "experience");
	private final ProposalRepository proposals;
	private final HistoricalEvaluationStore history;
	private final ObjectMapper mapper;
	private final StoredHistoricalInputLookup storedInputs;

	public JpaHistoricalExplanationSource(ProposalRepository proposals, HistoricalEvaluationStore history,
		ObjectMapper mapper, StoredHistoricalInputLookup storedInputs) {
		this.proposals = proposals;
		this.history = history;
		this.mapper = mapper;
		this.storedInputs = storedInputs;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Source> find(RuleExplanationProvider.Input input) {
		try {
			if (input == null || input.proposalId() <= 0 || input.viewerId() <= 0 || input.attemptId() == null
				|| input.inputFingerprint() == null || !input.inputFingerprint().matches("[a-f0-9]{64}")) {
				return Optional.empty();
			}
			MatchProposal proposal = proposals.findById(input.proposalId()).orElse(null);
			if (proposal == null || proposal.getId() == null || proposal.getId() != input.proposalId()
				|| !proposal.isProposed()
				|| proposal.getUserLow() != input.viewerId() && proposal.getUserHigh() != input.viewerId()
				|| !matchesInput(proposal, input)) {
				return Optional.empty();
			}
			HistoricalEvaluationEvidence evidence = history.find(input.proposalId()).orElse(null);
			if (evidence == null || !matchesStored(proposal, evidence) || !matchesInput(proposal, input)) {
				return Optional.empty();
			}
			// 원 UUID·판정·공개 범위 검증에 더해 두 입력이 실제 과거 저장 행과 일치해야 한다.
			if (storedInputs.find(evidence.left(), evidence.metAt()).isEmpty()
				|| storedInputs.find(evidence.right(), evidence.metAt()).isEmpty()) {
				return Optional.empty();
			}
			String viewer = evidence.left().userId() == input.viewerId() ? "A"
				: evidence.right().userId() == input.viewerId() ? "B" : null;
			if (viewer == null) {
				return Optional.empty();
			}
			List<String> displayed = evidence.displayEvidenceIdsByViewer().get(viewer);
			if (displayed.isEmpty()) {
				return Optional.empty();
			}
			List<HistoricalEvaluationEvidence.SourceEvidence> allowed = evidence.sourceEvidence().stream()
				.filter(item -> displayed.contains(item.id()) && TOPICS.contains(item.topic())).toList();
			if (allowed.size() != displayed.size()) {
				return Optional.empty();
			}
			Map<String, Object> reference = Map.of("encounter_id", input.proposalId(),
				"evaluation_ref", input.inputFingerprint(), "version_ref", evidence.manifest().get("releaseId"));
			List<Map<String, Object>> records = allowed.stream()
				.map(item -> record(reference, item)).toList();
			Set<String> sourceRefs = allowed.stream().map(HistoricalEvaluationEvidence.SourceEvidence::sourceRef)
				.collect(Collectors.toUnmodifiableSet());
			List<String> conflicts = evidence.conflictingSourceRefsByViewer().get(viewer).stream()
				.filter(sourceRefs::contains).toList();
			Map<String, Object> context = Map.of("reference", reference, "viewer", viewer, "evidence", records,
				"conflicting_source_refs", conflicts);
			Map<String, Object> scope = Map.of("reference", reference, "viewer", viewer,
				"version", evidence.scopeVersion(), "model_evidence_ids", List.of(),
				"display_evidence_ids", displayed);
			Map<String, Object> manifest = new LinkedHashMap<>(evidence.manifest());
			return Optional.of(new Source(input.viewerId(), Map.copyOf(manifest),
				Map.of("context", context, "scope", scope)));
		} catch (RuntimeException exception) {
			// 저장·검증 실패를 현재 입력으로 보충하거나 원문·개인정보를 로그에 복사하지 않는다.
			return Optional.empty();
		}
	}

	private boolean matchesInput(MatchProposal proposal, RuleExplanationProvider.Input input) {
		if (proposal.getEvaluation() == null) {
			return false;
		}
		String binding = ExplanationBinding.of(proposal, mapper);
		return input.inputFingerprint().equals(ExplanationJournal.boundFingerprint(
			proposal.getEvaluation(), mapper, binding))
			&& input.inputFingerprint().equals(ExplanationJournal.boundFingerprint(
				input.evaluation(), mapper, binding));
	}

	private boolean matchesStored(MatchProposal proposal, HistoricalEvaluationEvidence evidence) {
		if (proposal.getId() != evidence.matchingId()
			|| proposal.getUserLow() != Math.min(evidence.left().userId(), evidence.right().userId())
			|| proposal.getUserHigh() != Math.max(evidence.left().userId(), evidence.right().userId())
			|| !evidence.metAt().equals(proposal.getMetAt())
			|| !evidence.policyVersion().equals(proposal.getPolicyVersion())) {
			return false;
		}
		Object stored = proposal.getEvaluation().get(HistoricalEvaluationEvidence.STORAGE_KEY);
		return stored instanceof Map<?, ?>
			&& evidence.equals(mapper.convertValue(stored, HistoricalEvaluationEvidence.class));
	}

	private static Map<String, Object> record(Map<String, Object> reference,
		HistoricalEvaluationEvidence.SourceEvidence item) {
		Map<String, Object> summary = Map.of("id", item.id(), "direction", item.direction(),
			"kind", item.kind(), "topic", item.topic(), "fact", item.fact());
		return Map.of("reference", reference, "source_ref", item.sourceRef(), "evidence", summary);
	}
}
