package com.e106.tingle.rule.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 새 설문 회차의 기본 조합 전환·되돌리기.
 * 두 ID 칸은 null 이어도 반드시 보내야 한다. 칸을 빠뜨리면(실수로 파일 버전으로 되돌리는 일을 막기 위해) 400.
 *
 * @param releaseId 가리킬 게시 조합. null 이면 서버 설정의 파일 버전으로 되돌린다
 * @param expectedPreviousReleaseId 지금 가리키고 있다고 알고 있는 조합(없으면 null). 다르면 409 CHANNEL_CHANGED
 */
public record ActivationInput(@JsonProperty(required = true) UUID releaseId,
	@JsonProperty(required = true) UUID expectedPreviousReleaseId,
	@NotBlank @Size(max = 1000) String reason) {
}
