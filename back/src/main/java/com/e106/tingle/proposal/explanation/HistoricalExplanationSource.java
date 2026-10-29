package com.e106.tingle.proposal.explanation;

import java.util.Map;
import java.util.Optional;

/**
 * 같은 판정 당시의 원본 비교·열람자·공개 허용 결정을 조회하는 신뢰된 서버 어댑터.
 * Evaluation의 점수/reasonKeys나 현재 프로필에서 fact를 역추정하지 않는다.
 * 실제 생산자와 저장 경로가 연결되지 않으면 empty를 반환한다. 공개 HTTP 입력 포트가 아니다.
 */
public interface HistoricalExplanationSource {

	Optional<Source> find(RuleExplanationProvider.Input input);

	/** viewerId는 실제 원본의 A/B 연결과 인증 주체를 검증한 결과여야 한다. */
	record Source(long viewerId, Map<String, Object> manifest, Map<String, Object> contextBundle) {
	}
}
