package com.e106.tingle.survey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param surveyVersion 현재 설문 조회에서 받은 manifest.surveyVersion (예: SIS-Q-1.1-100). 저장 한도 30자
 */
public record CreateSessionRequest(@NotBlank @Size(max = 30) String surveyVersion) {
}
