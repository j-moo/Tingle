package com.e106.tingle.proposal.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.e106.tingle.IntegrationTestSupport;
import com.e106.tingle.TestcontainersConfiguration;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.ProposalRepository;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence.Snapshot;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence.SourceEvidence;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationStore;
import com.e106.tingle.proposal.evaluation.HistoricalEvidenceVerifier;
import com.e106.tingle.settings.snapshot.SettingsChangeCoordinator;
import com.e106.tingle.settings.snapshot.SettingsSnapshotService;
import com.e106.tingle.settings.snapshot.SettingsSnapshotView;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 JSONB 조회를 검증하되 당시 원본 검증기는 합성 근거를 대조하는 대역이다. */
@SpringBootTest(properties = "app.match-explanations.scheduling-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class HistoricalExplanationSourceIntegrationTest extends IntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-10-29T01:00:00Z").truncatedTo(ChronoUnit.MICROS);
	private static final String POLICY = "synthetic-v1";
	@Autowired
	private ProposalRepository proposals;
	@Autowired
	private HistoricalEvaluationStore history;
	@Autowired
	private JpaHistoricalExplanationSource source;
	@Autowired
	private JsonMapper mapper;
	@Autowired
	private SettingsChangeCoordinator settingsChanges;
	@Autowired
	private SettingsSnapshotService snapshots;
	@Autowired
	private PlatformTransactionManager transactions;
	@MockitoBean
	private HistoricalEvidenceVerifier verifier;
	private long firstId;
	private long secondId;
	private long proposalId;
	private SettingsSnapshotView firstInput;
	private SettingsSnapshotView secondInput;

	@BeforeEach
	void storeSyntheticPast() throws Exception {
		given(clock.instant()).willReturn(NOW.minusSeconds(120));
		JsonNode first = login("kakao", newSocialId());
		JsonNode second = login("kakao", newSocialId());
		completeOnboarding(first);
		completeOnboarding(second);
		firstId = userIdOf(first);
		secondId = userIdOf(second);
		firstInput = captureInput(firstId);
		secondInput = captureInput(secondId);
		given(clock.instant()).willReturn(NOW);
		MatchProposal proposal = proposals.saveAndFlush(MatchProposal.proposed(firstId, secondId, NOW));
		proposalId = proposal.getId();
		jdbc.update("update matching set met_at=?,policy_version=?,evaluation=cast(? as jsonb) where id=?",
			java.sql.Timestamp.from(NOW.minusSeconds(60)), POLICY,
			"{\"decision\":\"MATCHED\",\"ruleVersion\":\"synthetic-v1\"}", proposalId);
		HistoricalEvaluationEvidence captured = evidence();
		given(verifier.verify(any(), any())).willAnswer(call -> captured.equals(call.getArgument(1)));
		history.store(captured);
	}

	@Test
	void storedPastSurvivesCurrentProfileChangesAndUsesOriginalViewerOrder() {
		RuleExplanationProvider.Input left = input(secondId);
		RuleExplanationProvider.Input right = input(firstId);
		HistoricalExplanationSource.Source initial = source.find(left).orElseThrow();
		jdbc.update("update profile set introduction=? where user_id in (?,?)",
			"현재 프로필 변경은 과거 비교 근거가 아니다.", firstId, secondId);
		JsonNode leftBundle = mapper.valueToTree(source.find(left).orElseThrow().contextBundle());
		JsonNode rightBundle = mapper.valueToTree(source.find(right).orElseThrow().contextBundle());
		assertThat(source.find(left).orElseThrow()).isEqualTo(initial);
		assertThat(leftBundle.at("/context/viewer").asString()).isEqualTo("A");
		assertThat(rightBundle.at("/context/viewer").asString()).isEqualTo("B");
		assertThat(leftBundle.at("/context/evidence").size()).isEqualTo(1);
		assertThat(leftBundle.at("/context/evidence/0/evidence/id").asString()).isEqualTo("e1");
		assertThat(rightBundle.at("/context/evidence/0/evidence/id").asString()).isEqualTo("e2");
		assertThat(leftBundle.toString()).doesNotContain("약속을 준비하는 시점");
		assertThat(rightBundle.toString()).doesNotContain("연락 빈도");
		assertThat(leftBundle.at("/scope/model_evidence_ids"))
			.isEqualTo(leftBundle.at("/scope/display_evidence_ids"));
		assertThat(leftBundle.at("/context/reference/evaluation_ref").asString()).isEqualTo(left.inputFingerprint());
	}

	@Test
	void changedSnapshotOrEvaluationCannotUseAnOlderAcceptedInput() {
		RuleExplanationProvider.Input old = input(secondId);
		assertThat(source.find(old)).isPresent();
		jdbc.update("update matching set evaluation=jsonb_set(evaluation,"
			+ "'{historicalEvidence,left,inputHash}',to_jsonb(cast(? as text))) where id=?",
			"c".repeat(64), proposalId);
		assertThat(source.find(old)).isEmpty();
		assertThat(source.find(input(secondId))).isEmpty();
	}

	@Test
	void changedProposalGenerationAndMissingProofNeverFallBackToCurrentData() {
		RuleExplanationProvider.Input old = input(secondId);
		jdbc.update("update matching set proposed_at=proposed_at+interval '1 second' where id=?", proposalId);
		assertThat(source.find(old)).isEmpty();
		given(verifier.verify(any(), any())).willReturn(false);
		assertThat(source.find(input(secondId))).isEmpty();
		assertThat(source.find(input(secondId + firstId + 1000))).isEmpty();
	}

	@Test
	void missingActualSnapshotCannotBeReplacedByCurrentProfileOrStoredSummary() {
		RuleExplanationProvider.Input accepted = input(secondId);
		assertThat(source.find(accepted)).isPresent();
		jdbc.update("delete from settings_snapshot where user_id=? and settings_version=?",
			firstId, firstInput.settingsVersion());

		assertThat(source.find(accepted)).isEmpty();
	}

	@Test
	void tamperedStoredInputIsRejectedEvenWhenTheHistoricalSummaryIsUnchanged() {
		RuleExplanationProvider.Input accepted = input(secondId);
		assertThat(source.find(accepted)).isPresent();
		jdbc.update("update settings_snapshot set input_hash=? where user_id=? and settings_version=?",
			"c".repeat(64), secondId, secondInput.settingsVersion());

		assertThat(source.find(accepted)).isEmpty();
	}

	private SettingsSnapshotView captureInput(long userId) {
		return new TransactionTemplate(transactions).execute(status -> {
			// 공통 fixture의 직접 SQL 변경 후에는 새 세대를 만들고 기존 입력을 보존한다.
			settingsChanges.advanceAndCapture(userId);
			Long version = jdbc.queryForObject("select settings_version from user_setting where user_id=?",
				Long.class, userId);
			return snapshots.find(userId, version).orElseThrow();
		});
	}

	@Override
	protected long finishSurvey(long userId) {
		long personalityId = super.finishSurvey(userId);
		// 공통 fixture의 DB now()를 이 시나리오의 원본 입력 저장 이전 시각에 맞춘다.
		jdbc.update("update survey set completed_at=? where user_id=?",
			java.sql.Timestamp.from(NOW.minusSeconds(180)), userId);
		return personalityId;
	}

	private RuleExplanationProvider.Input input(long viewerId) {
		MatchProposal proposal = proposals.findById(proposalId).orElseThrow();
		String fingerprint = ExplanationJournal.boundFingerprint(proposal.getEvaluation(), mapper,
			ExplanationBinding.of(proposal, mapper));
		return new RuleExplanationProvider.Input(proposalId, viewerId, UUID.randomUUID(), fingerprint,
			proposal.getEvaluation());
	}

	private HistoricalEvaluationEvidence evidence() {
		Map<String, String> manifest = Map.ofEntries(
			Map.entry("releaseId", UUID.randomUUID().toString()), Map.entry("surveyVersion", POLICY),
			Map.entry("wordingVersion", POLICY), Map.entry("scoringVersion", POLICY),
			Map.entry("typeModelVersion", POLICY), Map.entry("analysisVersion", POLICY),
			Map.entry("typeReferenceVersion", POLICY), Map.entry("matchingVersion", POLICY),
			Map.entry("productPolicyVersion", POLICY), Map.entry("engineVersion", POLICY),
			Map.entry("contentHash", "a".repeat(64)));
		return new HistoricalEvaluationEvidence(1, proposalId, NOW.minusSeconds(60), POLICY,
			new Snapshot(secondId, UUID.randomUUID(), secondInput.inputHash(), secondInput.settingsVersion(),
				Snapshot.sourceRef(secondId, secondInput.settingsVersion())),
			new Snapshot(firstId, UUID.randomUUID(), firstInput.inputHash(), firstInput.settingsVersion(),
				Snapshot.sourceRef(firstId, firstInput.settingsVersion())), manifest, "synthetic-public-v1",
			List.of(new SourceEvidence("e1", "A_TO_B", "ONE_WAY_PREFERENCE_FIT", "contact",
				"B의 실제 연락 빈도가 A의 허용 범위에 들어간다.", "synthetic-contact"),
				new SourceEvidence("e2", "BOTH", "DIFFERENCE", "planning",
					"약속을 준비하는 시점에는 차이가 있다.", "synthetic-planning")),
			Map.of("A", List.of("e1"), "B", List.of("e2")),
			Map.of("A", List.of(), "B", List.of()));
	}
}
