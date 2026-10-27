package com.e106.tingle.survey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param surveyVersion 현재 설문 조회에서 받은 manifest.surveyVersion (예: SIS-Q-1.1-100). 통합 명세상 100자까지
 */
public record CreateSessionRequest(@NotBlank @Size(max = 100) String surveyVersion) {
}
