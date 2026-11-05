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

	@Column(nullable = false)
	private Long userId;

	// 이 회차가 고정한 설문 버전(예: SIS-Q-1.1-100)
	@Column(name = "survey_version", nullable = false, length = 30)
	private String versionCode;

	private Instant completedAt;

	// 선택 나이 정보(metadata) 저장마다 1씩 올리는 변경 번호. 응답 ETag 이고, metadata 저장은 If-Match 로 이 값을 맞춰야 한다.
	@ColumnDefault("0")
	@Column(nullable = false)
	private long revision;

	// 설문 중 고른 나이 정보는 {"metadata": {...}} 안에 둔다. 옛 회차의 다른 키(answers·audit 등)는 그대로 보존한다.
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String responseContext;

	// 아래 칸은 ERD에 남은 옛 설계(재시도 키·공개 UUID·문항 사본)의 기존 값 보존용이다.
	// 현재 명세는 쓰지 않으므로 새 회차는 비워 두고 읽지도 않는다. 칸 자체는 ERD와 같게 매핑만 유지한다.
	@Getter(AccessLevel.NONE)
	private UUID clientRequestId;

	@Getter(AccessLevel.NONE)
	private UUID legacyPublicId;

	@Getter(AccessLevel.NONE)
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String definitionSnapshot;

	static SurveyResponse start(long userId, String versionCode) {
		SurveyResponse response = new SurveyResponse();
		response.userId = userId;
		response.versionCode = versionCode;
		return response;
	}

	void changeContext(String context) {
		responseContext = context;
		revision++;
	}

	void complete(Instant now) {
		completedAt = now;
	}

	public boolean isCompleted() {
		return completedAt != null;
	}
}
