package com.e106.tingle.encounter.peer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.e106.tingle.encounter.EncounterEvaluationGateway;
import com.e106.tingle.encounter.EncounterEvaluationWorker;
import com.e106.tingle.encounter.EncounterReceipts;
import com.e106.tingle.encounter.MatchingEvaluationRuntimeVerifier;
import com.e106.tingle.encounter.MatchingEvaluationTypes.Evaluation;
import com.e106.tingle.encounter.StoredEncounterInputLookup;
import com.e106.tingle.global.error.BusinessException;
import com.e106.tingle.global.error.ErrorCode;
import com.e106.tingle.proposal.MatchProposal;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence;
import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence.Snapshot;
import com.e106.tingle.proposal.evaluation.HistoricalEvidenceVerifier;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 키링 판정을 그대로 따르는 매칭 판정. 두 키링이 각자 matching.c 로 계산해 통과(KR_ACCEPT)하고 서로 확인한 만남만
 * CONFIRMED 기록이 되고, 서버는 양쪽 CONFIRMED 원본의 서명·당시 소유·당시 설정을 검증해 VERIFIED_BOTH 로 둔다.
 * 그래서 양쪽 원본이 VERIFIED_BOTH 로 남아 있으면 QUALIFIED 다. 서버는 점수를 다시 계산하지 않는다(점수 칸 없음).
 * 승인·게시된 키링 규칙 조합(MATCHING·PRODUCT_POLICY)이 기록의 버전과 같을 때만 판정한다.
 */
@Component
@RequiredArgsConstructor
// 평가 워커가 꺼져 있으면 만들지 않는다. 그래야 승격이 "접수 완료"를 돌려주고 평가·기한 종료 없이 쌓이지 않고,
// 이전처럼 정책 대기(BUSINESS_POLICY_PENDING)로 응답한다.
@ConditionalOnProperty(name = {"app.keyring.peer.enabled", "app.keyring.peer.encounters-enabled",
	"app.keyring.peer.business-intake-enabled", "app.encounter.evaluation.enabled"}, havingValue = "true")
public class KeyringVerdictEvaluator
	implements EncounterEvaluationGateway, MatchingEvaluationRuntimeVerifier, HistoricalEvidenceVerifier {

	static final String SOURCE = "KEYRING_VERDICT";
	// 키링 판정은 설문·성향 분석 결과를 서버에서 쓰지 않는다. 근거 형식이 요구하는 칸에 사용하지 않았다고 남긴다.
	private static final String NOT_USED = "NOT_USED";
	private static final Map<String, List<String>> NO_VIEWER_EVIDENCE = Map.of("A", List.of(), "B", List.of());

	private final JdbcTemplate jdbc;
	private final JsonMapper mapper;
	private final EncounterReceipts receipts;
	private final StoredEncounterInputLookup inputs;
	private final Ter2BusinessReleaseResolver releases;

	@Override
	public VerifiedResult evaluate(Input input, String inputHash) {
		if (input == null || inputHash == null || !inputHash.equals(receipts.canonicalHash(input))) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		// 접수 때 지금 승인된 조합인지 확인하고(requireApprovedRelease) 그 조합을 작업에 고정했다. 평가는 고정한 조합으로 한다.
		// 그사이 설정이 다음 조합으로 바뀌어도 처리 중인 만남이 영구 차단되지 않는다.
		Decision decision = decide(input);
		return new VerifiedResult(input.generationId(), inputHash, Outcome.QUALIFIED, decision.evaluation(),
			decision.evidence());
	}

	@Override
	public void requireApprovedRelease(Input input, Snapshot left, Snapshot right) {
		if (input == null || input.releaseId() == null
			|| !input.releaseId().equals(releases.resolve(input.policyVersion(), input.matchingRuleVersion(),
				input.metAt()))) {
			throw unresolved();
		}
	}

	/** AI 점수 응답은 이 판정 방식에서 받지 않는다. */
	@Override
	public Verification verify(Input input, String inputHash, Snapshot left, Snapshot right, Evaluation evaluation) {
		throw unresolved();
	}

	/** 저장된 판정·근거가 지금 다시 만든 키링 판정과 같을 때만 믿는다(제안 설명 등에서 읽을 때). */
	@Override
	public boolean verify(MatchProposal matching, HistoricalEvaluationEvidence evidence) {
		try {
			if (matching == null || evidence == null || matching.getId() == null || matching.getEvaluation() == null) {
				return false;
			}
			List<String> saved = jdbc.queryForList("select request_state->>cast(? as text) from matching_participant "
				+ "where matching_id=? and user_id=?", String.class, EncounterEvaluationWorker.WORKFLOW_KEY,
				matching.getId(), matching.getUserLow());
			if (saved.size() != 1 || saved.getFirst() == null) {
				return false;
			}
			EncounterEvaluationWorker.Work work = mapper.readValue(saved.getFirst(),
				EncounterEvaluationWorker.Work.class);
			Input input = work.input();
			// 완료 트랜잭션 안에서는 아직 RUNNING 이다(근거 저장 뒤 SUCCEEDED 기록).
			if (input == null || input.matchingId() != matching.getId()
				|| !Set.of(EncounterEvaluationWorker.State.RUNNING, EncounterEvaluationWorker.State.SUCCEEDED)
					.contains(work.state())
				|| !Objects.equals(input.generationId(), work.generationId())
				|| !receipts.canonicalHash(input).equals(work.inputHash())) {
				return false;
			}
			// 이미 고정한 근거는 당시 조합으로 확인한다. 지금 설정이 다음 조합으로 바뀌어도 과거 근거는 유효하다.
			Decision expected = decide(input);
			Map<String, Object> stored = new LinkedHashMap<>(matching.getEvaluation());
			stored.remove(HistoricalEvaluationEvidence.STORAGE_KEY);
			stored.remove("matchExplanations");
			return expected.evidence().equals(evidence)
				&& mapper.valueToTree(expected.evaluation()).equals(mapper.valueToTree(stored));
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private Decision decide(Input input) {
		if (input.generationId() == null || input.metAt() == null || input.left() == null || input.right() == null
			|| input.leftUserId() <= 0 || input.rightUserId() <= input.leftUserId()
			|| input.left().userId() != input.leftUserId() || input.right().userId() != input.rightUserId()
			|| input.policyVersion() == null || input.matchingRuleVersion() == null) {
			throw unresolved();
		}
		Snapshot left = inputs.find(input.left(), input.metAt()).orElseThrow(KeyringVerdictEvaluator::unresolved);
		Snapshot right = inputs.find(input.right(), input.metAt()).orElseThrow(KeyringVerdictEvaluator::unresolved);
		// 근거에 남기는 조합 정보는 설정값이 아니라 그 조합 행에서 읽는다(검증·승인·게시를 마친 조합만).
		List<Map<String, Object>> release = jdbc.queryForList("select engine_version,approval_hash from rule_release "
			+ "where id=? and validation_passed=true and approval_hash=validation_hash and published_at is not null",
			input.releaseId());
		if (release.size() != 1) {
			throw unresolved();
		}
		// 양쪽 키링의 CONFIRMED 원본이 지금도 검증 상태(철회·폐기 안 됨)로 남아 있어야 한다.
		if (!Ter2BusinessStoredEvidence.matches(jdbc, input.left(), input.metAt())
			|| !Ter2BusinessStoredEvidence.matches(jdbc, input.right(), input.metAt())) {
			throw unresolved();
		}
		Map<String, String> manifest = Map.ofEntries(Map.entry("releaseId", input.releaseId().toString()),
			Map.entry("matchingVersion", input.matchingRuleVersion()),
			Map.entry("productPolicyVersion", input.policyVersion()),
			Map.entry("engineVersion", (String)release.getFirst().get("engine_version")),
			Map.entry("contentHash", (String)release.getFirst().get("approval_hash")),
			Map.entry("surveyVersion", NOT_USED),
			Map.entry("wordingVersion", NOT_USED), Map.entry("scoringVersion", NOT_USED),
			Map.entry("typeModelVersion", NOT_USED), Map.entry("analysisVersion", NOT_USED),
			Map.entry("typeReferenceVersion", NOT_USED));
		HistoricalEvaluationEvidence evidence = new HistoricalEvaluationEvidence(1, input.matchingId(),
			input.metAt(), input.policyVersion(), left, right, manifest, SOURCE, List.of(), NO_VIEWER_EVIDENCE,
			NO_VIEWER_EVIDENCE);
		Map<String, Object> evaluation = Map.of("source", SOURCE, "eligible", true,
			"releaseId", input.releaseId().toString(), "matchingVersion", input.matchingRuleVersion(),
			"productPolicyVersion", input.policyVersion(), "leftSnapshotId", left.snapshotId().toString(),
			"rightSnapshotId", right.snapshotId().toString());
		return new Decision(evaluation, evidence);
	}

	private static BusinessException unresolved() {
		return new BusinessException(ErrorCode.POLICY_UNRESOLVED);
	}

	private record Decision(Map<String, Object> evaluation, HistoricalEvaluationEvidence evidence) {
	}
}
