package com.ssafy.e106.features.survey.data

import kotlinx.serialization.Serializable

@Serializable enum class AnalysisStatus { PENDING, SUCCEEDED, INSUFFICIENT, FAILED }
@Serializable data class AnalysisReceipt(
    val resultId: Long, val sessionId: Long, val analysisVersion: String, val status: AnalysisStatus,
)
@Serializable data class AnalysisManifest(val surveyVersion: String, val analysisVersion: String)
/** 가입 완료 화면은 점수나 유형을 표시하지 않는다. 필요한 상태와 부족 문항만 받는다. */
@Serializable data class AnalysisResult(
    val resultId: Long, val sessionId: Long, val manifest: AnalysisManifest,
    val status: AnalysisStatus, val missingQuestionIds: List<String> = emptyList(),
)
/** 결과 본문은 저장하지 않고 재조회에 필요한 식별 정보만 계정별로 보관한다. */
@Serializable data class AnalysisReference(val sessionId: Long, val resultId: Long, val analysisVersion: String)
