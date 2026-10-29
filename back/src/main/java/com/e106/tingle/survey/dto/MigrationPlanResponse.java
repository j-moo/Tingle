package com.e106.tingle.survey.dto;

import java.util.List;

/**
 * 새 설문 버전 이관 미리보기. DB는 바꾸지 않는다.
 *
 * @param preservedQuestionIds 답을 그대로 옮길 문항
 * @param reconfirmQuestionIds 새 버전에서 다시 답할 문항
 * @param incompatibleQuestionIds 답했지만 새 버전으로 옮길 수 없는 문항
 * @param policyHash 이관 규칙 파일의 해시. 실제 이관 요청에 그대로 보낸다
 */
public record MigrationPlanResponse(long sourceSessionId, String targetSurveyVersion,
	List<String> preservedQuestionIds, List<String> reconfirmQuestionIds, List<String> incompatibleQuestionIds,
	String policyHash) {
}
