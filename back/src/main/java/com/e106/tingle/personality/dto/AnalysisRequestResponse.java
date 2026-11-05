package com.e106.tingle.personality.dto;

/**
 * 분석 요청 응답(202). 결과는 GET /api/analysis-results/{resultId} 로 본다.
 */
public record AnalysisRequestResponse(long resultId, long sessionId, String analysisVersion, String status) {
}
