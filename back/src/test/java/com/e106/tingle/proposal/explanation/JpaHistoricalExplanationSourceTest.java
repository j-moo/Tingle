package com.e106.tingle.proposal.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationStore;
import com.e106.tingle.proposal.evaluation.StoredHistoricalInputLookup;
import com.e106.tingle.settings.snapshot.SettingsSnapshotView;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 저장 조회 대역으로 소비 계약을 검사하며 실제 DB·snapshot 생산 검증 성공을 뜻하지 않는다. */
class JpaHistoricalExplanationSourceTest {

	private static final long MATCHING_ID = 17;
	private static final UUID ATTEMPT = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
	private static final Instant MET_AT = Instant.parse("2026-10-29T01:00:00Z");
	private static final Instant PROPOSED_AT = Instant.parse("2026-10-29T01:01:00Z");
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final ProposalRepository proposals = mock(ProposalRepository.class);
	private final HistoricalEvaluationStore history = mock(HistoricalEvaluationStore.class);
	private final StoredHistoricalInputLookup storedInputs = mock(StoredHistoricalInputLookup.class);
	private final JpaHistoricalExplanationSource source = new JpaHistoricalExplanationSource(
		proposals, history, mapper, storedInputs);

	@Test
	void originalLeftAndRightDetermineViewersAndOnlyDisplayAllowedFactsAreForwarded() {
		Fixture fixture = fixture();
		configure(fixture);

		HistoricalExplanationSource.Source left = source.find(input(fixture.proposal(), 19)).orElseThrow();
		HistoricalExplanationSource.Source right = source.find(input(fixture.proposal(), 7)).orElseThrow();
		JsonNode leftBundle = mapper.valueToTree(left.contextBundle());
		JsonNode rightBundle = mapper.valueToTree(right.contextBundle());

		assertThat(left.viewerId()).isEqualTo(19);
		assertThat(leftBundle.at("/context/viewer").asString()).isEqualTo("A");
		assertThat(rightBundle.at("/context/viewer").asString()).isEqualTo("B");
		assertThat(leftBundle.at("/context/evidence").size()).isEqualTo(1);
		assertThat(leftBundle.toString()).contains("synthetic contact summary A")
			.doesNotContain("synthetic planning summary B").doesNotContain("source-b");
		assertThat(rightBundle.toString()).contains("synthetic planning summary B")
			.doesNotContain("synthetic contact summary A").doesNotContain("source-a");
		assertThat(leftBundle.at("/context/evidence/0/source_ref").asString()).isEqualTo("source-a");
		assertThat(leftBundle.at("/context/conflicting_source_refs/0").asString()).isEqualTo("source-a");
		assertThat(leftBundle.at("/scope/model_evidence_ids").isEmpty()).isTrue();
		assertThat(leftBundle.at("/scope/display_evidence_ids/0").asString()).isEqualTo("e1");
		assertThat(left.manifest()).containsEntry("releaseId", fixture.evidence().manifest().get("releaseId"));
		assertThat(leftBundle.at("/scope/reference")).isEqualTo(leftBundle.at("/context/reference"));
		assertThat(leftBundle.at("/context/reference/evaluation_ref").asString())
			.isEqualTo(input(fixture.proposal(), 19).inputFingerprint());
	}

	@Test
	void currentDecisionChangesCannotReuseAnEarlierRunFingerprint() {
		Fixture fixture = fixture();
		configure(fixture);
		RuleExplanationProvider.Input prior = input(fixture.proposal(), 19);
		fixture.proposal().getEvaluation().put("decision", "synthetic changed decision");

		assertThat(source.find(prior)).isEmpty();
	}

	@Test
	void changedProposalGenerationCannotReuseAnEarlierRunFingerprint() {
		Fixture fixture = fixture();
		configure(fixture);
		RuleExplanationProvider.Input prior = input(fixture.proposal(), 19);
		ReflectionTestUtils.setField(fixture.proposal(), "proposedAt", PROPOSED_AT.plusSeconds(1));

		assertThat(source.find(prior)).isEmpty();
	}

	@Test
	void inputPayloadMustMatchTheClaimedFingerprintAsWellAsTheCurrentDecision() {
		Fixture fixture = fixture();
		configure(fixture);
		RuleExplanationProvider.Input valid = input(fixture.proposal(), 19);
		Map<String, Object> changed = new LinkedHashMap<>(valid.evaluation());
		changed.put("decision", "synthetic tampered input");
		RuleExplanationProvider.Input forged = new RuleExplanationProvider.Input(MATCHING_ID, 19, ATTEMPT,
			valid.inputFingerprint(), changed);

		assertThat(source.find(forged)).isEmpty();
	}

	@Test
	void differentReadSourceSnapshotDoesNotMatchThePersistedEvidence() {
		Fixture fixture = fixture();
		configure(fixture);
		HistoricalEvaluationEvidence value = fixture.evidence();
		HistoricalEvaluationEvidence changed = copy(value, new HistoricalEvaluationEvidence.Snapshot(19,
			UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"), "c".repeat(64)),
			value.displayEvidenceIdsByViewer());
		when(history.find(MATCHING_ID)).thenReturn(Optional.of(changed));

		assertThat(source.find(input(fixture.proposal(), 19))).isEmpty();
	}

	@Test
	void persistedSourceSnapshotChangesInvalidateAnExistingRun() {
		Fixture fixture = fixture();
		configure(fixture);
		RuleExplanationProvider.Input prior = input(fixture.proposal(), 19);
		HistoricalEvaluationEvidence changed = copy(fixture.evidence(),
			new HistoricalEvaluationEvidence.Snapshot(19, fixture.evidence().left().snapshotId(), "c".repeat(64)),
			fixture.evidence().displayEvidenceIdsByViewer());
		fixture.proposal().getEvaluation().put(HistoricalEvaluationEvidence.STORAGE_KEY, object(changed));
		when(history.find(MATCHING_ID)).thenReturn(Optional.of(changed));

		assertThat(source.find(prior)).isEmpty();
	}

	@Test
	void otherExplanationJournalUpdatesDoNotChangeTheHistoricalInput() {
		Fixture fixture = fixture();
		configure(fixture);
		RuleExplanationProvider.Input prior = input(fixture.proposal(), 19);
		ExplanationJournal.Claim peer = ExplanationJournal.claim(fixture.proposal().getEvaluation(), 7, ATTEMPT,
			mapper, ExplanationBinding.of(fixture.proposal(), mapper), PROPOSED_AT.plusSeconds(1));
		ReflectionTestUtils.setField(fixture.proposal(), "evaluation", peer.evaluation());

		assertThat(source.find(prior)).isPresent();
	}

	@Test
	void absentOrUnverifiedHistoryIsNotReconstructedFromReasonKeys() {
		Fixture fixture = fixture();
		configure(fixture);
		ReflectionTestUtils.setField(fixture.proposal(), "evaluation", new LinkedHashMap<>(
			Map.of("reasonKeys", List.of("synthetic-contact-fit"))));
		when(history.find(MATCHING_ID)).thenReturn(Optional.empty());

		assertThat(source.find(input(fixture.proposal(), 19))).isEmpty();
	}

	@Test
	void viewerWithoutDisplayPermissionReceivesNoSourceFacts() {
		Fixture fixture = fixture();
		HistoricalEvaluationEvidence changed = copy(fixture.evidence(), fixture.evidence().left(),
			Map.of("A", List.of(), "B", List.of("e2")));
		fixture.proposal().getEvaluation().put(HistoricalEvaluationEvidence.STORAGE_KEY, object(changed));
		configure(new Fixture(fixture.proposal(), changed));

		assertThat(source.find(input(fixture.proposal(), 19))).isEmpty();
	}

	@Test
	void invalidIdentityAndFingerprintCannotReadHistoricalFacts() {
		assertThat(source.find(null)).isEmpty();
		assertThat(source.find(new RuleExplanationProvider.Input(0, 19, ATTEMPT, "a".repeat(64), Map.of())))
			.isEmpty();
		assertThat(source.find(new RuleExplanationProvider.Input(MATCHING_ID, 0, ATTEMPT, "a".repeat(64), Map.of())))
			.isEmpty();
		assertThat(source.find(new RuleExplanationProvider.Input(MATCHING_ID, 19, null, "a".repeat(64), Map.of())))
			.isEmpty();
		assertThat(source.find(new RuleExplanationProvider.Input(MATCHING_ID, 19, ATTEMPT, null, Map.of())))
			.isEmpty();
		assertThat(source.find(new RuleExplanationProvider.Input(MATCHING_ID, 19, ATTEMPT, "invalid", Map.of())))
			.isEmpty();

		verifyNoInteractions(proposals, history, storedInputs);
	}

	@Test
	void missingUnproposedOrForeignProposalCannotReadHistoricalFacts() {
		Fixture fixture = fixture();
		when(proposals.findById(MATCHING_ID)).thenReturn(Optional.empty());
		assertThat(source.find(input(fixture.proposal(), 19))).isEmpty();
		when(proposals.findById(MATCHING_ID)).thenReturn(Optional.of(fixture.proposal()));
		assertThat(source.find(input(fixture.proposal(), 31))).isEmpty();
		ReflectionTestUtils.setField(fixture.proposal(), "storageStatus", MatchProposal.MatchingStatus.WAITING);
		assertThat(source.find(input(fixture.proposal(), 19))).isEmpty();

		verifyNoInteractions(history);
	}

	@Test
	void historicalReadFailuresRemainUnavailable() {
		Fixture fixture = fixture();
		configure(fixture);
		when(history.find(MATCHING_ID)).thenThrow(new IllegalStateException("Synthetic history unavailable"));

		assertThat(source.find(input(fixture.proposal(), 19))).isEmpty();
	}

	@Test
	void missingStoredInputCannotBeReconstructedFromTheHistoricalSummary() {
		Fixture fixture = fixture();
		configure(fixture);
		when(storedInputs.find(fixture.evidence().right(), MET_AT)).thenReturn(Optional.empty());

		assertThat(source.find(input(fixture.proposal(), 19))).isEmpty();
	}

	private void configure(Fixture fixture) {
		when(proposals.findById(MATCHING_ID)).thenReturn(Optional.of(fixture.proposal()));
		when(history.find(MATCHING_ID)).thenReturn(Optional.of(fixture.evidence()));
		for (HistoricalEvaluationEvidence.Snapshot snapshot : List.of(fixture.evidence().left(),
			fixture.evidence().right())) {
			when(storedInputs.find(snapshot, MET_AT)).thenReturn(Optional.of(new SettingsSnapshotView(
				snapshot.userId(), snapshot.settingsVersion(), "settings-source-evidence-v1", "{}",
				snapshot.inputHash(), MET_AT.minusSeconds(60))));
		}
	}

	private RuleExplanationProvider.Input input(MatchProposal proposal, long viewerId) {
		Map<String, Object> evaluation = new LinkedHashMap<>(proposal.getEvaluation());
		evaluation.remove(ExplanationJournal.FIELD);
		String fingerprint = ExplanationJournal.boundFingerprint(evaluation, mapper,
			ExplanationBinding.of(proposal, mapper));
		return new RuleExplanationProvider.Input(MATCHING_ID, viewerId, ATTEMPT, fingerprint, evaluation);
	}

	private Fixture fixture() {
		Map<String, String> manifest = new LinkedHashMap<>();
		for (String key : ExplanationArtifactCatalog.MANIFEST_FIELDS) {
			manifest.put(key, "synthetic-v1");
		}
		manifest.put("releaseId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
		manifest.put("contentHash", "f".repeat(64));
		HistoricalEvaluationEvidence evidence = new HistoricalEvaluationEvidence(1, MATCHING_ID, MET_AT, "policy-v1",
			new HistoricalEvaluationEvidence.Snapshot(19, UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
				"a".repeat(64), 0L, HistoricalEvaluationEvidence.Snapshot.sourceRef(19, 0)),
			new HistoricalEvaluationEvidence.Snapshot(7, UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
				"b".repeat(64), 0L, HistoricalEvaluationEvidence.Snapshot.sourceRef(7, 0)),
			manifest, "synthetic-scope-v1", List.of(
				new HistoricalEvaluationEvidence.SourceEvidence("e1", "A_TO_B", "ONE_WAY_PREFERENCE_FIT", "contact",
					"synthetic contact summary A", "source-a"),
				new HistoricalEvaluationEvidence.SourceEvidence("e2", "BOTH", "DIFFERENCE", "planning",
					"synthetic planning summary B", "source-b")),
			Map.of("A", List.of("e1"), "B", List.of("e2")),
			Map.of("A", List.of("source-a", "source-b"), "B", List.of()));
		MatchProposal proposal = MatchProposal.proposed(7, 19, PROPOSED_AT);
		ReflectionTestUtils.setField(proposal, "id", MATCHING_ID);
		ReflectionTestUtils.setField(proposal, "metAt", MET_AT);
		ReflectionTestUtils.setField(proposal, "policyVersion", "policy-v1");
		Map<String, Object> evaluation = new LinkedHashMap<>();
		evaluation.put("decision", "synthetic historical decision");
		evaluation.put(HistoricalEvaluationEvidence.STORAGE_KEY, object(evidence));
		ReflectionTestUtils.setField(proposal, "evaluation", evaluation);
		return new Fixture(proposal, evidence);
	}

	private Map<String, Object> object(HistoricalEvaluationEvidence evidence) {
		return mapper.convertValue(evidence, new TypeReference<LinkedHashMap<String, Object>>() { });
	}

	private static HistoricalEvaluationEvidence copy(HistoricalEvaluationEvidence value,
		HistoricalEvaluationEvidence.Snapshot left, Map<String, List<String>> display) {
		return new HistoricalEvaluationEvidence(value.schemaVersion(), value.matchingId(), value.metAt(),
			value.policyVersion(), left, value.right(), value.manifest(), value.scopeVersion(),
			value.sourceEvidence(), display, value.conflictingSourceRefsByViewer());
	}

	private record Fixture(MatchProposal proposal, HistoricalEvaluationEvidence evidence) {
	}
}
