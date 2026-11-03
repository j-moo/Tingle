package com.ssafy.e106.features.survey.data

import kotlinx.serialization.Serializable

@Serializable enum class SurveyChangeKind { RETEST, MIGRATION }
@Serializable data class SurveyChangeIntent(
    val sourceSessionId: Long, val targetSurveyVersion: String,
    val kind: SurveyChangeKind, val policyHash: String? = null,
)
@Serializable data class SurveyMigrationPlan(
    val sourceSessionId: Long, val targetSurveyVersion: String,
    val preservedQuestionIds: List<String>, val reconfirmQuestionIds: List<String>,
    val incompatibleQuestionIds: List<String>, val policyHash: String,
)
@Serializable data class SurveyBookmark(val sessionId: Long, val analysis: AnalysisReference? = null)
