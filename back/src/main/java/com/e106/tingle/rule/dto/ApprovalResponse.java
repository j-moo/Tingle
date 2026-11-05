package com.e106.tingle.rule.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * @param candidateHash 승인한 조합 내용의 해시
 * @param reviewerSubject 승인한 운영자(토큰 sub = 회원번호)
 */
public record ApprovalResponse(UUID approvalId, UUID releaseId, String candidateHash, String reviewerSubject,
	Instant approvedAt) {
}
