package com.e106.tingle.rule.dto;

import java.util.List;
import java.util.UUID;

/**
 * 검증 한 번의 결과. 검사마다 기대값(expected)과 실제값(actual)을 남긴다.
 *
 * @param status PASSED(모든 검사 통과) / FAILED
 * @param engineVersion 검증한 서버 규칙 엔진 버전
 */
public record ValidationResponse(UUID validationRunId, UUID releaseId, String candidateHash, String status,
	List<Check> checks, String engineVersion) {

	public record Check(String code, boolean passed, String detail, String expected, String actual) {
	}
}
