package com.e106.tingle.rule.dto;

import com.e106.tingle.rule.RuleKind;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * 규칙 정의 초안 생성·수정 요청.
 *
 * @param schemaVersion 정의 형식 버전. 서버에 등록된 (kind, schemaVersion) 만 받는다
 * @param document 정의 본문(JSON 객체). 형식은 kind·schemaVersion 으로 검사한다
 * @param changeReason 변경 이유. 작업 기록(rule_audit)에 남는다
 */
public record ArtifactInput(
	@NotNull RuleKind kind,
	@NotBlank @Size(max = 100) String version,
	@NotBlank @Size(max = 100) String schemaVersion,
	@NotNull JsonNode document,
	@NotBlank @Size(max = 1000) String changeReason) {
}
