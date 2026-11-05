package com.e106.tingle.rule;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 새 설문 회차가 쓸 게시 조합을 가리키는 포인터(행은 이름 "current" 하나).
 * releaseId 가 비어 있으면 서버 설정(app.survey.current-version)의 파일 버전을 쓴다.
 * surveyVersion 은 가리키는 게시 조합의 설문 버전 사본이다(게시본은 바뀌지 않으므로 함께 바꿔 두고 한 번에 읽는다).
 */
@Entity
@Table(name = "rule_channel")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RuleChannel {

	static final String CURRENT = "current";

	@Id
	@Column(length = 30)
	private String name;
	private UUID releaseId;
	@Column(length = 30)
	private String surveyVersion;
	@Column(nullable = false)
	private Instant updatedAt;

	void point(UUID releaseId, String surveyVersion, Instant now) {
		this.releaseId = releaseId;
		this.surveyVersion = surveyVersion;
		this.updatedAt = now;
	}
}
