package com.e106.tingle.survey;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 설문 응답 회차 (ERD survey_responses). 앱의 "설문 세션" 하나가 이 행 하나다.
 * 완료 시각이 없으면 DRAFT, 있으면 COMPLETED 이고, 완료 뒤에는 원응답을 바꾸지 않는다(재응답은 새 회차).
 * 같은 회원·버전의 미완료 회차는 하나뿐이다(DB 부분 유니크 인덱스).
 */
@Entity
@Table(name = "survey_responses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SurveyResponse {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "response_id")
	private Long id;

	// 앱에 보여 주는 회차 ID. 내부 번호(response_id)를 그대로 내보내면 몇 번째 회차인지 드러나서 따로 둔다.
	// DB 기본값은 개발 서버(ddl-auto: update)가 이미 행이 있는 테이블에 이 컬럼을 추가할 때 기존 행을 채우기 위한 것.
	@Column(nullable = false, unique = true, columnDefinition = "uuid default gen_random_uuid()")
	private UUID publicId;

	@Column(nullable = false)
	private Long userId;

	// 이 회차가 고정한 설문 버전(예: SIS-Q-1.1-100)
	@Column(nullable = false, columnDefinition = "text")
	private String versionCode;

	// 회차를 만든 요청의 Idempotency-Key. 같은 키로 다시 오면 같은 회차를 돌려준다.
	@Column(nullable = false)
	private UUID clientRequestId;

	// {"answers": {"Q001": 4, ...}, "metadata": {"ownAge": null, ...}}
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String responseJson;

	// 동시 저장 검사용 변경 번호(앱에는 ETag 로 전달). 저장할 때마다 JPA 가 1씩 올린다.
	@Version
	@Column(nullable = false)
	private long revision;

	private Instant completedAt;

	static SurveyResponse start(long userId, String versionCode, UUID clientRequestId, String responseJson) {
		SurveyResponse response = new SurveyResponse();
		response.publicId = UUID.randomUUID();
		response.userId = userId;
		response.versionCode = versionCode;
		response.clientRequestId = clientRequestId;
		response.responseJson = responseJson;
		return response;
	}

	public boolean isCompleted() {
		return completedAt != null;
	}
}
