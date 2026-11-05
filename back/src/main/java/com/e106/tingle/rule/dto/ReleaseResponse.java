package com.e106.tingle.rule.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.e106.tingle.rule.RuleKind;

/**
 * @param status DRAFT / VALIDATED(지금 내용으로 검증 통과) / APPROVED(지금 내용으로 승인됨) / PUBLISHED
 * @param candidateHash 지금 조합 내용(정의 해시들 + 엔진 버전)의 해시. 초안을 고치면 바뀐다
 * @param validationRunId 마지막 검증 번호. 검증한 적이 없으면 null
 * @param validationHash 마지막 검증 때의 후보 해시. candidateHash 와 다르면 그 검증은 지난 것이다
 * @param approval 지금 내용에 대한 승인. 없거나 내용이 바뀌었으면 null
 */
public record ReleaseResponse(UUID releaseId, String name, String status, String engineVersion,
	String candidateHash, List<Item> artifacts, UUID validationRunId, String validationHash,
	ApprovalResponse approval, Instant publishedAt) {

	public record Item(RuleKind kind, UUID artifactId, String version, String contentHash) {
	}
}
