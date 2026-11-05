package com.e106.tingle.rule;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자가 만든 규칙 정의(설문 문항·이관 규칙 등) 한 버전. 게시(publishedAt) 전 초안만 고칠 수 있고, 게시한 뒤에는 바꾸지 않는다.
 * 게시본을 고치려면 새 version 으로 새 정의를 만든다.
 */
@Entity
@Table(name = "rule_artifact", uniqueConstraints = @UniqueConstraint(name = "uq_rule_artifact_kind_version",
	columnNames = {"kind", "version"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RuleArtifact {

	@Id
	private UUID id;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30, updatable = false)
	private RuleKind kind;
	@Column(nullable = false, length = 100)
	private String version;
	@Column(nullable = false, length = 100)
	private String schemaVersion;
	@Column(nullable = false)
	private int revision;
	// 키를 정렬한 JSON 문자열. contentHash 는 이 문자열과 kind·version·schemaVersion 으로 계산한다.
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String document;
	@Column(nullable = false, length = 64)
	private String contentHash;
	@Column(nullable = false, updatable = false)
	private Long createdBy;
	@Column(nullable = false, updatable = false)
	private Instant createdAt;
	private Instant publishedAt;

	RuleArtifact(RuleKind kind, long createdBy, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.kind = kind;
		this.revision = -1;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
	}

	// 새 내용으로 바꾸고 revision 을 올린다(새로 만들면 0).
	void change(String version, String schemaVersion, String document, String contentHash) {
		this.version = version;
		this.schemaVersion = schemaVersion;
		this.document = document;
		this.contentHash = contentHash;
		this.revision++;
	}

	void publish(Instant now) {
		if (publishedAt == null) {
			publishedAt = now;
		}
	}

	boolean isPublished() {
		return publishedAt != null;
	}
}
