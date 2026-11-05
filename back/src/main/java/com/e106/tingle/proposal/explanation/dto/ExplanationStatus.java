package com.e106.tingle.proposal.explanation.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 첫 제공에서는 검증된 규칙 설명만 성공 결과로 공개한다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ExplanationStatus(
	String status,
	String source,
	String text,
	List<String> evidenceKeys,
	String promptVersion
) {
	public ExplanationStatus {
		if (evidenceKeys == null) {
			throw new IllegalArgumentException("Explanation evidence keys are required");
		}
		evidenceKeys = List.copyOf(evidenceKeys);
		if ("SUCCEEDED".equals(status)) {
			if (!"RULE_FALLBACK".equals(source) || text == null || text.isBlank()
				|| promptVersion != null && (promptVersion.isBlank() || promptVersion.length() > 100)) {
				throw new IllegalArgumentException("Invalid successful rule explanation");
			}
		} else if ("PENDING".equals(status) || "FAILED".equals(status)) {
			if (source != null || text != null || promptVersion != null || !evidenceKeys.isEmpty()) {
				throw new IllegalArgumentException("Unsuccessful explanation must not expose content");
			}
		} else {
			throw new IllegalArgumentException("Invalid explanation status");
		}
	}
}
