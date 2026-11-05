package com.e106.tingle.rule.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * @param releaseId 가리키는 게시 조합. null 이면 서버 설정의 파일 버전
 * @param surveyVersion 새 설문 회차가 쓰는 설문 버전
 * @param updatedAt 마지막 전환 시각. 전환한 적이 없으면 null
 */
public record ChannelResponse(UUID releaseId, String surveyVersion, Instant updatedAt) {
}
