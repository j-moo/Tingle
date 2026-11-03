package com.ssafy.e106.features.survey.ui

enum class SurveyRecoveryStage {
    AnswersIncomplete, Insufficient, RetestConfirmation, AnalysisVersionUnsupported,
    MigrationLoading, MigrationReview, Migrating, MigrationPolicyChanged,
    MigrationUnsupported, MigrationLoadFailed, MigrationOutcomeUnknown,
}

/** 문항 ID는 서버 결과에서, 순서/본문은 해당 회차의 questionnaire에서 가져온다. */
data class SurveyMissingQuestion(val id: String, val ordinal: Int? = null, val text: String? = null)

/** 사용자가 확인한 이관 응답 그대로 전달한다. 화면에는 버전/해시 등 내부 값을 노출하지 않는다. */
data class SurveyMigrationReview(
    val sourceSessionId: Long,
    val targetSurveyVersion: String,
    val policyHash: String,
    val preservedQuestionIds: List<String>,
    val reconfirmQuestionIds: List<String>,
    val incompatibleQuestionIds: List<String>,
) {
    // 같은 답을 유지하면서 다시 확인하라는 계획은 임의 실행하지 않는다.
    // BE 규칙 파일의 목록 교집합 검증은 별도 계약 확인 대상이며 앱에서는 이 검사를 유지한다.
    internal val valid: Boolean get() = sourceSessionId > 0 && targetSurveyVersion.isNotBlank() &&
        targetSurveyVersion.length <= 30 && Regex("[0-9a-fA-F]{64}").matches(policyHash) &&
        listOf(preservedQuestionIds, reconfirmQuestionIds, incompatibleQuestionIds).all { ids ->
            ids.all { it.isNotBlank() && it.length <= 50 } && ids.distinct().size == ids.size
        } && preservedQuestionIds.none { it in reconfirmQuestionIds || it in incompatibleQuestionIds } &&
        reconfirmQuestionIds.none { it in incompatibleQuestionIds }
}

sealed interface SurveyRecoveryAction {
    data object RefreshSession : SurveyRecoveryAction
    data object ReloadResult : SurveyRecoveryAction
    data object ReviewRetest : SurveyRecoveryAction
    data class StartOrResumeRetest(val surveyVersion: String) : SurveyRecoveryAction
    data object CheckCurrentSurvey : SurveyRecoveryAction
    data object ReloadMigrationPlan : SurveyRecoveryAction
    data class ConfirmMigration(val plan: SurveyMigrationReview) : SurveyRecoveryAction
    data class CheckMigrationOutcome(val plan: SurveyMigrationReview) : SurveyRecoveryAction
    data object Back : SurveyRecoveryAction
}

data class SurveyRecoveryState(
    val stage: SurveyRecoveryStage,
    val sourceSessionId: Long? = null,
    val missingQuestions: List<SurveyMissingQuestion> = emptyList(),
    val migration: SurveyMigrationReview? = null,
    // 재검사 안내 전에 호출부가 현재 설문을 조회한 값. 없으면 먼저 재조회한다.
    val retestSurveyVersion: String? = null,
) {
    val busy: Boolean get() = stage in setOf(SurveyRecoveryStage.MigrationLoading, SurveyRecoveryStage.Migrating)
    val visibleMissing: List<SurveyMissingQuestion> get() = missingQuestions.filter { it.id.isNotBlank() }.distinctBy { it.id }
    val validMigration: SurveyMigrationReview? get() = migration?.takeIf { it.valid && it.sourceSessionId == sourceSessionId }
    val primaryAction: SurveyRecoveryAction? get() {
        if (busy || sourceSessionId == null || sourceSessionId <= 0) return null
        return when (stage) {
            SurveyRecoveryStage.AnswersIncomplete -> SurveyRecoveryAction.RefreshSession
            SurveyRecoveryStage.Insufficient -> if (visibleMissing.isEmpty()) SurveyRecoveryAction.ReloadResult else SurveyRecoveryAction.ReviewRetest
            SurveyRecoveryStage.RetestConfirmation -> retestSurveyVersion?.takeIf { it.isNotBlank() && it.length <= 30 }
                ?.let { SurveyRecoveryAction.StartOrResumeRetest(it) } ?: SurveyRecoveryAction.CheckCurrentSurvey
            SurveyRecoveryStage.AnalysisVersionUnsupported, SurveyRecoveryStage.MigrationUnsupported -> SurveyRecoveryAction.CheckCurrentSurvey
            SurveyRecoveryStage.MigrationReview -> validMigration?.let { SurveyRecoveryAction.ConfirmMigration(it) }
                ?: SurveyRecoveryAction.ReloadMigrationPlan
            SurveyRecoveryStage.MigrationOutcomeUnknown -> validMigration?.let { SurveyRecoveryAction.CheckMigrationOutcome(it) }
                ?: SurveyRecoveryAction.ReloadMigrationPlan
            else -> SurveyRecoveryAction.ReloadMigrationPlan
        }
    }
}
