package com.e106.tingle.encounter.peer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 키링 판정을 그대로 따르는 매칭 판정. 두 키링이 각자 matching.c 로 계산해 통과(KR_ACCEPT)하고 서로 확인한 만남만
 * CONFIRMED 기록이 되고, 서버는 양쪽 CONFIRMED 원본의 서명·당시 소유·당시 설정을 검증해 VERIFIED_BOTH 로 둔다.
 * 그래서 양쪽 원본이 VERIFIED_BOTH 로 남아 있으면 QUALIFIED 다. 서버는 점수를 다시 계산하지 않는다(점수 칸 없음).
 * 승인·게시된 키링 규칙 조합(MATCHING·PRODUCT_POLICY)이 기록의 버전과 같을 때만 판정한다.
 */
@Component
@RequiredArgsConstructor
public class KeyringVerdictEvaluator
	implements EncounterEvaluationGateway, MatchingEvaluationRuntimeVerifier, HistoricalEvidenceVerifier {

	static final String SOURCE = "KEYRING_VERDICT";
	// 키링 판정은 설문·성향 분석 결과로 점수를 다시 계산하지 않는다. 근거 형식이 요구하는 칸에 사용하지 않았다고 남긴다.
	// (매칭 이유 근거는 SurveyMatchEvidence가 당시 설문 5쌍에서 따로 만든다.)
	private static final String NOT_USED = "NOT_USED";

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
			// 주의: 근거를 지금 코드로 다시 만들어 통째로 비교한다. SurveyMatchEvidence의 문장·판정 규칙을 바꾸면
			// 그 규칙에 걸리는 기존 매칭은 검증에 실패해 매칭 이유가 안 나온다. 바꿀 때는 이전 규칙을 남겨 둘 것.
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
		// 매칭 이유 근거: 만남 당시 설정 기록이 가리키는 설문 회차의 답으로 만든다. 다시 계산해도 같은 값이 나온다.
		SurveyMatchEvidence.Result reasons = SurveyMatchEvidence.build(answers(input.left()), answers(input.right()));
		HistoricalEvaluationEvidence evidence = new HistoricalEvaluationEvidence(1, input.matchingId(),
			input.metAt(), input.policyVersion(), left, right, manifest, SOURCE, reasons.evidence(),
			reasons.displayByViewer(), Map.of("A", List.of(), "B", List.of()));
		Map<String, Object> evaluation = Map.of("source", SOURCE, "eligible", true,
			"releaseId", input.releaseId().toString(), "matchingVersion", input.matchingRuleVersion(),
			"productPolicyVersion", input.policyVersion(), "leftSnapshotId", left.snapshotId().toString(),
			"rightSnapshotId", right.snapshotId().toString());
		return new Decision(evaluation, evidence);
	}

	/** 만남 당시 설정 기록의 설문 회차(본인 것, SIS-Q-1.1-100)의 답. 없거나 형식이 다르면 빈 값이라 근거를 만들지 않는다. */
	private Map<String, JsonNode> answers(ParticipantInput participant) {
		Map<String, JsonNode> values = new HashMap<>();
		// surveyId는 숫자로 바꾸지 않고 글자로 비교한다. 기록 형식이 이상해도 오류 대신 빈 결과(근거 없음)가 된다.
		jdbc.query("select a.question_id,a.answer_type,a.answer_value from settings_snapshot s "
			+ "join survey v on v.user_id=s.user_id and v.survey_version=? "
			+ "and v.id::text=s.input_json::jsonb->'analysis'->>'surveyId' "
			+ "join survey_answer a on a.survey_id=v.id "
			+ "where s.user_id=? and s.settings_version=? and s.input_json::jsonb->'analysis'->>'surveyVersion'=?",
			row -> {
				String type = row.getString("answer_type");
				String value = row.getString("answer_value");
				try {
					if ("STRING".equals(type)) {
						values.put(row.getString("question_id"), mapper.getNodeFactory().stringNode(value));
					} else if ("NUMBER".equals(type) || "ARRAY".equals(type)) {
						values.put(row.getString("question_id"), mapper.readTree(value));
					}
				} catch (RuntimeException malformed) {
					// 설명 재료가 깨졌다고 매칭 판정까지 막지 않는다. 그 문항만 근거에서 빠진다.
					values.remove(row.getString("question_id"));
				}
			}, SurveyMatchEvidence.SURVEY_VERSION, participant.userId(), participant.settingsVersion(),
			SurveyMatchEvidence.SURVEY_VERSION);
		return values;
	}

	private static BusinessException unresolved() {
		return new BusinessException(ErrorCode.POLICY_UNRESOLVED);
	}

	private record Decision(Map<String, Object> evaluation, HistoricalEvaluationEvidence evidence) {
	}
}
