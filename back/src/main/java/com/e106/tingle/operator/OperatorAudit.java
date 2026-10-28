package com.e106.tingle.operator;

import java.time.Instant;

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
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * canonical operator_audit의 저장 모델. 이 매핑만으로 관련 API나 운영 기능의 구현 완료를 뜻하지 않는다.
 * ID 논리 참조는 서비스에서 검증하며, 업무 발생 시각은 호출자가 실제 Clock 값으로 확정한다.
 */
@Entity
@Table(name = "operator_audit", indexes = {
	@Index(name = "ix_operator_audit_time", columnList = "occurred_at"),
	@Index(name = "ix_operator_audit_operator", columnList = "operator_id,occurred_at"),
	@Index(name = "ix_operator_audit_retention", columnList = "kind,occurred_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OperatorAudit {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@Column(name = "operator_id")
	private Long operatorId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "kind", nullable = false, columnDefinition = "operator_audit_kind")
	private OperatorAuditKind kind;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "action", nullable = false, columnDefinition = "operator_action")
	private OperatorAction action;

	@Column(name = "target_user_id")
	private Long targetUserId;

	@Column(name = "target_service_case_id")
	private Long targetServiceCaseId;

	@Column(name = "target_keyring_id")
	private Long targetKeyringId;

	@Column(name = "target_chat_report_id")
	private Long targetChatReportId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "result_code", nullable = false, columnDefinition = "operator_result")
	private OperatorResult resultCode;

	@Column(name = "reason", columnDefinition = "text")
	private String reason;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	public enum OperatorAuditKind {
		ACCESS,
		PERMISSION
	}

	public enum OperatorAction {
		MEMBER_VIEW,
		REPORT_CHAT_VIEW,
		REPORT_RESOLVE,
		PROFILE_CORRECT,
		KEYRING_UNBIND,
		MEMBER_WITHDRAW,
		CASE_HANDLE,
		LOGIN_FAIL,
		OPERATOR_GRANT,
		OPERATOR_REVOKE
	}

	public enum OperatorResult {
		SUCCESS,
		DENIED,
		FAILED
	}
}
