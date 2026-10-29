package com.e106.tingle.proposal.explanation;

import com.e106.tingle.proposal.explanation.dto.ExplanationStatus;

/** 신뢰된 과거 문맥·공개 범위 연결 전에는 현재 값이나 샘플로 설명을 만들지 않는다. */
public class UnresolvedRuleExplanationProvider implements RuleExplanationProvider {
	@Override
	public ExplanationStatus explain(Input input) {
		return RuleExplanationProvider.unavailable();
	}
}
