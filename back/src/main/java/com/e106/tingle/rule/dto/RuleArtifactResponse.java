package com.e106.tingle.rule.dto;

import java.util.UUID;

import com.e106.tingle.rule.RuleKind;

import tools.jackson.databind.JsonNode;

/**
 * @param revision 초안을 고칠 때마다 1씩 오른다. 수정 요청의 If-Match 에 "{revision}" 으로 넣는다
 * @param status DRAFT(고칠 수 있음) / PUBLISHED(게시돼 바뀌지 않음)
 */
public record RuleArtifactResponse(UUID artifactId, RuleKind kind, String version, String schemaVersion,
	int revision, JsonNode document, String contentHash, String status) {
}
