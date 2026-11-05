package com.e106.tingle.rule;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 규칙 관리 작업 기록(누가·언제·무엇을·왜). 고치거나 지우지 않는다.
 * 초안 작성자 확인(작성자 본인 승인 금지)도 이 기록으로 한다.
 */
@Entity
@Immutable
@Table(name = "rule_audit", indexes = @Index(name = "ix_rule_audit_target", columnList = "target_id,occurred_at"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RuleAudit {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private Action action;
	private UUID targetId;
	private Integer revision;
	@Column(length = 64)
	private String contentHash;
	// 검증 결과(검사별 기대값·실제값)나 채널 전환 전 값 같은 부가 정보
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String detail;
	@Column(columnDefinition = "text")
	private String reason;
	@Column(nullable = false)
	private Long actorId;
	@Column(nullable = false)
	private Instant occurredAt;

	RuleAudit(Action action, UUID targetId, Integer revision, String contentHash, String detail, String reason,
		long actorId, Instant occurredAt) {
		this.action = action;
		this.targetId = targetId;
		this.revision = revision;
		this.contentHash = contentHash;
		this.detail = detail;
		this.reason = reason;
		this.actorId = actorId;
		this.occurredAt = occurredAt;
	}

	enum Action {
		ARTIFACT_CREATE,
		ARTIFACT_UPDATE,
		RELEASE_CREATE,
		RELEASE_VALIDATE,
		RELEASE_APPROVE,
		RELEASE_PUBLISH,
		CHANNEL_SWITCH
	}
}
