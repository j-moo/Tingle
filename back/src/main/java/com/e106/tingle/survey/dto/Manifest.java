package com.e106.tingle.survey.dto;

import com.e106.tingle.survey.SurveyCatalog.SurveyDefinition;

/**
 * 회차가 고정한 설문 버전 정보. 지금은 설문 파일 하나라 설문·문구 버전과 파일 해시만 준다.
 * 통합 명세의 계산·유형·매칭 버전 필드는 해당 규칙 파일이 생기면 추가한다.
 */
public record Manifest(String surveyVersion, String wordingVersion, String contentHash) {

	public static Manifest of(SurveyDefinition definition) {
		return new Manifest(definition.surveyVersion(), definition.wordingVersion(), definition.contentHash());
	}
}
