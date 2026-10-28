package com.e106.tingle.personality;

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
 * canonical personality의 저장 모델. 이 매핑만으로 관련 API나 운영 기능의 구현 완료를 뜻하지 않는다.
 * ID 논리 참조는 서비스에서 검증하며, 업무 발생 시각은 호출자가 실제 Clock 값으로 확정한다.
 */
@Entity
@Table(name = "personality", indexes = {
	@Index(name = "uq_personality_survey_version", columnList = "survey_id,analysis_version", unique = true)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Personality {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@Column(name = "survey_id", nullable = false)
	private Long surveyId;

	@Column(name = "analysis_version", nullable = false, length = 30)
	private String analysisVersion;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "status", nullable = false, columnDefinition = "analysis_status")
	private AnalysisStatus status;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "result", columnDefinition = "jsonb")
	private String result;

	public enum AnalysisStatus {
		PENDING,
		SUCCEEDED,
		INSUFFICIENT,
		FAILED
	}
}
