package com.ssafy.e106.features.survey.data

import kotlinx.serialization.Serializable

/** 가입 단계 응답의 설문 복구 정보만 읽는다. 인증 기반의 가입 단계 모델은 변경하지 않는다. */
@Serializable
data class SurveyAccountRecovery(
    val surveySessionId: Long?,
    // 필드 누락과 명시적인 null은 다르다. 구버전 서버를 '설문 이력 없음'으로 오인하지 않는다.
    val latestCompletedSessionId: Long?,
    val latestAnalysis: AnalysisReceipt?,
    val activeResultId: Long?,
) {
    fun checked(): SurveyAccountRecovery = apply {
        require(listOfNotNull(surveySessionId, latestCompletedSessionId, activeResultId).all { it > 0 })
        require(surveySessionId == null || surveySessionId != latestCompletedSessionId)
        latestAnalysis?.let {
            require(it.sessionId == latestCompletedSessionId && it.resultId > 0 && it.analysisVersion.isNotBlank())
        }
    }

    /** 계산 복구를 먼저 제시하고, 완료·적용된 결과보다 작성 중 회차를 우선한다. */
    fun preferredSessionId(): Long? = when {
        latestCompletedSessionId == null -> surveySessionId
        latestAnalysis == null -> latestCompletedSessionId
        latestAnalysis.status in setOf(AnalysisStatus.PENDING, AnalysisStatus.FAILED) -> latestCompletedSessionId
        latestAnalysis.status == AnalysisStatus.SUCCEEDED && latestAnalysis.resultId != activeResultId -> latestCompletedSessionId
        else -> surveySessionId ?: latestCompletedSessionId
    }
}
