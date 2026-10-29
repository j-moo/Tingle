package com.e106.tingle.proposal.explanation;

import java.util.Arrays;

import com.e106.tingle.proposal.MatchProposal;

import tools.jackson.databind.ObjectMapper;

/** 같은 평가 JSON이어도 당사자·정책·제안 생성 세대가 바뀐 결과는 재사용하지 않는다. */
final class ExplanationBinding {
	private ExplanationBinding() {
	}

	static String of(MatchProposal proposal, ObjectMapper mapper) {
		return mapper.writeValueAsString(Arrays.asList(proposal.getId(), proposal.getUserLow(), proposal.getUserHigh(),
			proposal.getPolicyVersion(), proposal.getCreatedAt().toString()));
	}
}
