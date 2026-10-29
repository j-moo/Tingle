package com.e106.tingle.proposal.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class HistoricalEvidenceFixture {
	static final Instant MET = Instant.parse("2026-10-29T01:00:00Z");
	private HistoricalEvidenceFixture() {
	}

	static HistoricalEvaluationEvidence evidence(long id) {
		return evidence(id, 22, 11);
	}

	static HistoricalEvaluationEvidence evidence(long id, long left, long right) {
		return new HistoricalEvaluationEvidence(1, id, MET, "policy-test-1",
			new HistoricalEvaluationEvidence.Snapshot(left, UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
				"a".repeat(64)),
			new HistoricalEvaluationEvidence.Snapshot(right, UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
				"b".repeat(64)), manifest(), "display-test-1",
			List.of(new HistoricalEvaluationEvidence.SourceEvidence("e1", "BOTH", "SHARED_REPORTED_BEHAVIOR",
				"contact", "가상 근거: 두 사람은 연락 간격에 공통점이 있습니다.", "synthetic-source-1"),
				new HistoricalEvaluationEvidence.SourceEvidence("e2", "BOTH", "DIFFERENCE", "planning",
					"가상 근거: 약속을 준비하는 방식에 차이가 있습니다.", "synthetic-source-2")),
			Map.of("A", List.of("e1"), "B", List.of("e2")), Map.of("A", List.of(), "B", List.of()));
	}

	static Map<String, String> manifest() {
		return Map.ofEntries(Map.entry("releaseId", "cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
			Map.entry("surveyVersion", "synthetic-v1"), Map.entry("wordingVersion", "synthetic-v1"),
			Map.entry("scoringVersion", "synthetic-v1"), Map.entry("typeModelVersion", "synthetic-v1"),
			Map.entry("analysisVersion", "synthetic-v1"), Map.entry("typeReferenceVersion", "synthetic-v1"),
			Map.entry("matchingVersion", "synthetic-v1"), Map.entry("productPolicyVersion", "policy-test-1"),
			Map.entry("engineVersion", "synthetic-v1"), Map.entry("contentHash", "c".repeat(64)));
	}
}
