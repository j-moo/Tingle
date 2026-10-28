package com.e106.tingle.survey;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 설문 응답 회차 (ERD survey). 답은 survey_answer의 문항별 행으로 분리한다.
 * 완료 시각이 없으면 DRAFT, 있으면 COMPLETED 이고, 완료 뒤에는 원응답을 바꾸지 않는다(재응답은 새 회차).
 * 같은 회원·버전의 미완료 회차는 하나뿐이다(DB 부분 유니크 인덱스).
 */
@Entity
@Table(name = "survey", uniqueConstraints = {
	@UniqueConstraint(name = "uq_survey_user_request", columnNames = {"user_id", "client_request_id"}),
	@UniqueConstraint(name = "uq_survey_legacy_public_id", columnNames = "legacy_public_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SurveyResponse {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	// 이미 발급한 과거 공개 UUID를 보존한다. 새 회차에는 만들지 않으며 현재 API는 숫자 id만 사용한다.
	private UUID legacyPublicId;

	@Column(nullable = false)
	private Long userId;

	// 이 회차가 고정한 설문 버전(예: SIS-Q-1.1-100)
	@Column(name = "survey_version", nullable = false, length = 30)
	private String versionCode;

	// 회차를 만든 요청의 Idempotency-Key. 같은 키로 다시 오면 같은 회차를 돌려준다.
	private UUID clientRequestId;

	// 과거 응답의 answers 이외 루트 정보만 보존한다. 답은 여기에 중복 저장하지 않는다.
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String responseContext;

	// 새 회차는 실제 확인한 문항 파일·manifest·metadata schema를 고정한다. 과거 파일 증거는 추정하지 않는다.
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String definitionSnapshot;

	// 동시 저장 검사용 변경 번호(앱에는 ETag 로 전달). 저장할 때마다 JPA 가 1씩 올린다.
	@Version
	@ColumnDefault("0")
	@Column(nullable = false)
	private long revision;

	private Instant completedAt;

	static SurveyResponse start(long userId, String versionCode, UUID clientRequestId,
		String responseContext, String definitionSnapshot) {
		SurveyResponse response = new SurveyResponse();
		response.userId = userId;
		response.versionCode = versionCode;
		response.clientRequestId = clientRequestId;
		response.responseContext = responseContext;
		response.definitionSnapshot = definitionSnapshot;
		return response;
	}

	public boolean isCompleted() {
		return completedAt != null;
	}
}
