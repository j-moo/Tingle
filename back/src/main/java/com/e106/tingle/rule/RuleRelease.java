package com.e106.tingle.rule;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 함께 게시할 규칙 정의 조합(종류별 하나). 조합은 만든 뒤 바꾸지 않는다.
 * 검증·승인은 그때의 후보 해시(정의 내용 해시들 + 엔진 버전)를 남기고, 게시할 때 지금 해시와 같아야 한다.
 * 그래서 조합에 든 초안을 고치면 해시가 달라져 이전 검증·승인이 저절로 무효가 된다.
 */
@Entity
@Table(name = "rule_release")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RuleRelease {

	@Id
	private UUID id;
	@Column(nullable = false, length = 100, updatable = false)
	private String name;
	@Column(nullable = false, length = 100, updatable = false)
	private String engineVersion;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb", updatable = false)
	private List<String> artifactIds;
	@Column(nullable = false, updatable = false)
	private Long createdBy;
	@Column(nullable = false, updatable = false)
	private Instant createdAt;
	private UUID validationId;
	@Column(length = 64)
	private String validationHash;
	private Boolean validationPassed;
	private UUID approvalId;
	@Column(length = 64)
	private String approvalHash;
	private Long approvedBy;
	private Instant approvedAt;
	private Long publishedBy;
	private Instant publishedAt;

	RuleRelease(String name, String engineVersion, List<UUID> artifactIds, long createdBy, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.name = name;
		this.engineVersion = engineVersion;
		this.artifactIds = artifactIds.stream().map(UUID::toString).toList();
		this.createdBy = createdBy;
		this.createdAt = createdAt;
	}

	List<UUID> artifactUuids() {
		return artifactIds.stream().map(UUID::fromString).toList();
	}

	void validated(String candidateHash, boolean passed) {
		this.validationId = UUID.randomUUID();
		this.validationHash = candidateHash;
		this.validationPassed = passed;
	}

	// 통과한 검증의 해시가 지금 후보 해시와 같고, 요청이 그 검증 결과를 보고 한 것인지.
	boolean isValidatedAs(String candidateHash, UUID requestedValidationId, String requestedHash) {
		return Boolean.TRUE.equals(validationPassed) && candidateHash.equals(validationHash)
			&& validationId.equals(requestedValidationId) && candidateHash.equals(requestedHash);
	}

	boolean isApprovedAs(String candidateHash) {
		return candidateHash.equals(approvalHash);
	}

	void approve(String candidateHash, long approver, Instant now) {
		this.approvalId = UUID.randomUUID();
		this.approvalHash = candidateHash;
		this.approvedBy = approver;
		this.approvedAt = now;
	}

	void publish(long publisher, Instant now) {
		this.publishedBy = publisher;
		this.publishedAt = now;
	}

	boolean isPublished() {
		return publishedAt != null;
	}
}
