package com.e106.tingle.survey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param targetSurveyVersion 옮겨 갈 설문 버전(새 회차의 survey_version). 저장 한도 30자
 * @param policyHash 미리보기 응답의 policyHash 그대로
 */
public record MigrationRequest(@NotBlank @Size(max = 30) String targetSurveyVersion, @NotBlank String policyHash) {
}
