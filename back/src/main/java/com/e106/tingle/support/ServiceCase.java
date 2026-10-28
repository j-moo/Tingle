package com.e106.tingle.support;

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
 * canonical service_case의 저장 모델. 이 매핑만으로 관련 API나 운영 기능의 구현 완료를 뜻하지 않는다.
 * ID 논리 참조는 서비스에서 검증하며, 업무 발생 시각은 호출자가 실제 Clock 값으로 확정한다.
 */
@Entity
@Table(name = "service_case", indexes = {
	@Index(name = "ix_service_case_requester", columnList = "requester_user_id,created_at"),
	@Index(name = "ix_service_case_worklist", columnList = "status,assigned_operator_id"),
	@Index(name = "ix_service_case_reply_due", columnList = "reply_due_at"),
	@Index(name = "ix_service_case_closed", columnList = "closed_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceCase {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@Column(name = "requester_user_id", nullable = false)
	private Long requesterUserId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "kind", nullable = false, columnDefinition = "service_case_kind")
	private ServiceCaseKind kind;

	@Column(name = "reason", nullable = false, columnDefinition = "text")
	private String reason;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "correction_field", columnDefinition = "profile_correction_field")
	private ProfileCorrectionField correctionField;

	@Column(name = "correction_value", length = 100)
	private String correctionValue;

	@Column(name = "lost_keyring_id")
	private Long lostKeyringId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "status", nullable = false, columnDefinition = "service_case_status")
	private ServiceCaseStatus status;

	@Column(name = "assigned_operator_id")
	private Long assignedOperatorId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "reply_due_at")
	private Instant replyDueAt;

	@Column(name = "closed_at")
	private Instant closedAt;

	public enum ServiceCaseKind {
		PROFILE_CORRECTION,
		LOST_WITHDRAWAL
	}

	public enum ProfileCorrectionField {
		BIRTH_DATE,
		GENDER,
		NICKNAME
	}

	public enum ServiceCaseStatus {
		RECEIVED,
		REVIEWING,
		NEEDS_INFO,
		COMPLETED,
		REJECTED,
		NO_RESPONSE
	}
}
