package com.e106.tingle.personality.dto;

import java.util.List;

import com.e106.tingle.personality.Personality;
import com.e106.tingle.personality.Personality.AnalysisStatus;

import tools.jackson.databind.JsonNode;

/**
 * 개인 결과 조회 응답. personality.result 의 AI 결과에서 명세 항목만 골라 낸다
 * (재시도 정보·내부 점수 원본·fit 값은 내보내지 않음).
 * SUCCEEDED 는 모든 항목, INSUFFICIENT 는 missingQuestionIds 만 채운다(부분 점수·참고 유형은 숨김).
 * PENDING·FAILED 는 결과 항목이 null 또는 빈 값이다.
 * 필드 이름은 AI 샘플 응답(2026-10-30, 테스트 resources/personality)과 같다.
 */
public record AnalysisResultResponse(long resultId, long sessionId, Manifest manifest, String status,
	JsonNode scores, String typeStatus, String primaryType, List<String> tiedTypes, List<Reading> readings,
	List<Reading> selfPreferenceDifferences, List<TypeReference> typeRecommendations,
	List<String> missingQuestionIds) {

	public static AnalysisResultResponse of(Personality personality, String surveyVersion, JsonNode result) {
		Manifest manifest = new Manifest(surveyVersion, personality.getAnalysisVersion());
		AnalysisStatus status = personality.getStatus();
		if (status == AnalysisStatus.SUCCEEDED && result != null) {
			return new AnalysisResultResponse(personality.getId(), personality.getSurveyId(), manifest, status.name(),
				result.get("scores"), string(result, "typeStatus"), string(result, "primaryType"),
				strings(result.path("tiedTypes")), result.path("readings").valueStream().map(Reading::of).toList(),
				result.path("selfPreferenceDifferences").valueStream().map(Reading::of).toList(),
				result.path("typeRecommendations").valueStream().map(TypeReference::of).toList(),
				strings(result.path("missingQuestionIds")));
		}
		List<String> missing = status == AnalysisStatus.INSUFFICIENT && result != null
			? strings(result.path("missingQuestionIds")) : List.of();
		return new AnalysisResultResponse(personality.getId(), personality.getSurveyId(), manifest, status.name(),
			null, null, null, List.of(), List.of(), List.of(), List.of(), missing);
	}

	public record Manifest(String surveyVersion, String analysisVersion) {
	}

	public record Reading(String key, String templateId, String text, List<String> evidenceQuestionIds,
		Long priority) {

		static Reading of(JsonNode node) {
			JsonNode priority = node.get("priority");
			return new Reading(string(node, "key"), string(node, "templateId"), string(node, "text"),
				strings(node.path("evidenceQuestionIds")), priority == null || !priority.isNumber()
					? null : priority.asLong());
		}
	}

	public record TypeReference(String typeCode, Double score, String mode, List<String> evidenceQuestionIds) {

		static TypeReference of(JsonNode node) {
			JsonNode score = node.get("score");
			return new TypeReference(string(node, "typeCode"),
				score == null || !score.isNumber() ? null : score.asDouble(), string(node, "mode"),
				strings(node.path("evidenceQuestionIds")));
		}
	}

	private static String string(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || !value.isString() ? null : value.asString();
	}

	private static List<String> strings(JsonNode array) {
		return array.valueStream().filter(JsonNode::isString).map(JsonNode::asString).toList();
	}
}
