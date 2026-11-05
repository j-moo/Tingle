package com.e106.tingle.proposal.explanation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.e106.tingle.proposal.explanation.dto.ExplanationStatus;

/** 실제 당시 판정 조회와 공개 허용 검증을 담당하는 서버 내부 연결 경계다. */
public interface RuleExplanationProvider {

	ExplanationStatus explain(Input input);

	record Input(long proposalId, long viewerId, UUID attemptId, String inputFingerprint,
		Map<String, Object> evaluation) {
		public Input {
			evaluation = Collections.unmodifiableMap(new LinkedHashMap<>(evaluation));
		}
	}

	static ExplanationStatus unavailable() {
		return new ExplanationStatus("FAILED", null, null, List.of(), null);
	}
}
