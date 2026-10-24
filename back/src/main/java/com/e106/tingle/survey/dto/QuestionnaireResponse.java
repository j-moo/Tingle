package com.e106.tingle.survey.dto;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * 설문 정의. questions 는 버전 파일의 문항을 표시 순서(ordinal)대로 그대로 준다.
 */
public record QuestionnaireResponse(Manifest manifest, int questionCount, List<JsonNode> questions,
	JsonNode metadataSchema) {
}
