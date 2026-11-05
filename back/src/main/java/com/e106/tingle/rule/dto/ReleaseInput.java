package com.e106.tingle.rule.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 게시할 규칙 정의 조합. 종류마다 하나씩, 설문(QUESTIONNAIRE)은 꼭 하나 들어 있어야 한다.
 *
 * @param engineVersion 이 조합을 실행할 규칙 엔진 버전. 서버 엔진과 같아야 검증을 통과한다
 */
public record ReleaseInput(
	@NotBlank @Size(max = 100) String name,
	@NotNull @Size(min = 1, max = 9) List<@NotNull UUID> artifactIds,
	@NotBlank @Size(max = 100) String engineVersion,
	@NotBlank @Size(max = 1000) String changeReason) {
}
