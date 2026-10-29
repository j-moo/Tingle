package com.e106.tingle.proposal.explanation;

import java.util.Optional;
import java.util.UUID;

import com.e106.tingle.proposal.explanation.dto.ExplanationStatus;

import tools.jackson.databind.JsonNode;

/**
 * 검증된 최소 근거만 AI 기존 규칙 엔진에 전달한다. 85 공통 AiInternalClient의 얇은 어댑터가 구현한다.
 * 어댑터는 내부 응답 5필드와 encounterId 일치를 검사한 뒤 공개 결과로 투영해야 한다.
 * 자체 HTTP/JWT 구현·모델 복제·GMS 직접 호출을 하지 않는다. 미연결 시 provider는 FAILED로 닫힌다.
 */
public interface RuleExplanationTransport {

	Optional<ExplanationStatus> explain(Request request);

	record Request(UUID attemptId, long contextId, JsonNode manifest, JsonNode contextBundle) {
		public Request {
			manifest = manifest.deepCopy();
			contextBundle = contextBundle.deepCopy();
		}
	}

}
