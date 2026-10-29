package com.e106.tingle.proposal.explanation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 설명 요청의 도메인 상태. 별도 공용 작업 ID를 노출하지 않는다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ExplanationTask(
	long proposalId,
	String kind,
	String status,
	String statusUrl,
	String errorCode,
	boolean retryable,
	int pollAfterSeconds
) {
	public ExplanationTask {
		if (proposalId < 1 || !"MATCH_EXPLANATION".equals(kind) || pollAfterSeconds < 0
			|| !("PENDING".equals(status) || "SUCCEEDED".equals(status) || "FAILED".equals(status))
			|| !("/api/match-proposals/" + proposalId + "/explanation").equals(statusUrl)) {
			throw new IllegalArgumentException("Invalid explanation task");
		}
	}
}
