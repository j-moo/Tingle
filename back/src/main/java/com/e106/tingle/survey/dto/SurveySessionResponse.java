package com.e106.tingle.survey.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * 설문 회차. 진행 정보(answeredCount, nextQuestionId)는 저장된 응답에서 계산한다.
 *
 * @param status DRAFT(작성 중) 또는 COMPLETED(완료, 더 이상 수정 불가)
 * @param revision 동시 저장 검사용 변경 번호. 응답 헤더 ETag 와 같다
 * @param nextQuestionId 표시 순서상 첫 미응답 문항. 모두 응답했으면 null
 * @param answers 응답한 문항만 표시 순서대로. "unknown"(모르겠음)도 응답이다
 */
public record SurveySessionResponse(
	UUID sessionId,
	Manifest manifest,
	String status,
	long revision,
	int answeredCount,
	int questionCount,
	String nextQuestionId,
	List<Answer> answers,
	JsonNode metadata,
	Instant completedAt
) {

	public record Answer(String questionId, JsonNode value) {
	}
}
