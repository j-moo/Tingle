package com.e106.tingle.encounter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.e106.tingle.proposal.evaluation.HistoricalEvaluationEvidence;

/** 공식 당시 입력·규칙·판정을 검증하는 내부 접점이다. 공개 요청의 성공 플래그를 신뢰하지 않는다. */
public interface EncounterEvaluationGateway {

	VerifiedResult evaluate(Input input, String inputHash);

	enum Outcome { QUALIFIED, NOT_MATCHED, INSUFFICIENT, UNRESOLVED }

	record ParticipantInput(long userId, long deviceId, UUID submissionId, UUID eventId, String requestHash,
		UUID settingsSnapshotId, long settingsVersion, UUID ownerGeneration, String inputHash, String proofHash) {
	}

	record Input(long matchingId, UUID generationId, long leftUserId, long rightUserId, Instant metAt,
		Instant opportunityExpiresAt, Instant counterpartDeadlineAt, String policyVersion, UUID releaseId,
		String matchingRuleVersion, ParticipantInput left, ParticipantInput right) {
	}

	/** 반환 전에 공식 증명·불변 입력과 평가 결과의 대응을 검증해야 한다. */
	record VerifiedResult(UUID generationId, String inputHash, Outcome outcome, Map<String, Object> evaluation,
		HistoricalEvaluationEvidence historicalEvidence) {
		/** 근거 없는 기존 호출은 실제 완료 저장기에서 성공으로 처리하지 않는다. */
		public VerifiedResult(UUID generationId, String inputHash, Outcome outcome, Map<String, Object> evaluation) {
			this(generationId, inputHash, outcome, evaluation, null);
		}
	}
}
