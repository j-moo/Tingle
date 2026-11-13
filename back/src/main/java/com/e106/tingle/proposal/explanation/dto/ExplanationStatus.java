package com.e106.tingle.proposal.explanation.dto;

import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 성공 결과는 규칙 설명(RULE_FALLBACK)이거나 GMS가 만든 설명(GENERATED)이다.
 * GENERATED의 promptVersion은 AI 서버 프롬프트의 SHA-256(소문자 64자리)이다. 값을 특정 버전으로 고정하지 않고
 * 형식만 확인한 뒤 받은 그대로 저장·조회한다(AI와 2026-11-13 합의). 문장 내용 검증은 AI 서버가 맡고,
 * 이 값은 어떤 프롬프트로 만든 설명인지 추적하는 기록이다. 그래서 AI가 프롬프트를 고쳐도 서버 수정이 필요 없다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ExplanationStatus(
	String status,
	String source,
	String text,
	List<String> evidenceKeys,
	String promptVersion
) {
	public static final String RULE_FALLBACK = "RULE_FALLBACK";
	public static final String GENERATED = "GENERATED";
	public static final Pattern GENERATED_VERSION = Pattern.compile("[a-f0-9]{64}");

	public ExplanationStatus {
		if (evidenceKeys == null) {
			throw new IllegalArgumentException("Explanation evidence keys are required");
		}
		evidenceKeys = List.copyOf(evidenceKeys);
		if ("SUCCEEDED".equals(status)) {
			boolean rule = RULE_FALLBACK.equals(source)
				&& (promptVersion == null || !promptVersion.isBlank() && promptVersion.length() <= 100);
			boolean generated = GENERATED.equals(source) && promptVersion != null
				&& GENERATED_VERSION.matcher(promptVersion).matches();
			if (!(rule || generated) || text == null || text.isBlank()) {
				throw new IllegalArgumentException("Invalid successful explanation");
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
