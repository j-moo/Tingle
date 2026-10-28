package com.e106.tingle.report;

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
 * canonical chat_report의 저장 모델. 이 매핑만으로 관련 API나 운영 기능의 구현 완료를 뜻하지 않는다.
 * ID 논리 참조는 서비스에서 검증하며, 업무 발생 시각은 호출자가 실제 Clock 값으로 확정한다.
 */
@Entity
@Table(name = "chat_report", indexes = {
	@Index(name = "ix_chat_report_worklist", columnList = "status,created_at"),
	@Index(name = "ix_chat_report_chat", columnList = "chat_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatReport {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@Column(name = "chat_id", nullable = false)
	private Long chatId;

	@Column(name = "reporter_participant_id", nullable = false)
	private Long reporterParticipantId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "reason", nullable = false, columnDefinition = "chat_report_reason")
	private ChatReportReason reason;

	@Column(name = "detail", columnDefinition = "text")
	private String detail;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "status", nullable = false, columnDefinition = "chat_report_status")
	private ChatReportStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "resolved_at")
	private Instant resolvedAt;

	public enum ChatReportReason {
		ABUSE,
		SEXUAL,
		SPAM,
		ETC
	}

	public enum ChatReportStatus {
		RECEIVED,
		REVIEWING,
		RESOLVED,
		DISMISSED
	}
}
