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
 * canonical service_case_history의 저장 모델. 이 매핑만으로 관련 API나 운영 기능의 구현 완료를 뜻하지 않는다.
 * ID 논리 참조는 서비스에서 검증하며, 업무 발생 시각은 호출자가 실제 Clock 값으로 확정한다.
 */
@Entity
@Table(name = "service_case_history", indexes = {
	@Index(name = "ix_service_case_history_case", columnList = "service_case_id,created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceCaseHistory {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@Column(name = "service_case_id", nullable = false)
	private Long serviceCaseId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "event", nullable = false, columnDefinition = "service_case_event")
	private ServiceCaseEvent event;

	@Column(name = "actor_user_id")
	private Long actorUserId;

	@Column(name = "message", columnDefinition = "text")
	private String message;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	public enum ServiceCaseEvent {
		INFO_REQUESTED,
		USER_REPLIED,
		ACTION_DONE,
		ACTION_FAILED,
		STATUS_CHANGED
	}
}
