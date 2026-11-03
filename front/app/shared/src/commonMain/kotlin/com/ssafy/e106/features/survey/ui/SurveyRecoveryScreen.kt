package com.ssafy.e106.features.survey.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** API 호출/회차 생성/응답 복사를 하지 않는 순수 화면. 작업은 확인한 상태와 함께 호출부로 전달한다. */
@Composable
fun SurveyRecoveryScreen(state: SurveyRecoveryState, onAction: (SurveyRecoveryAction) -> Unit, modifier: Modifier = Modifier) {
    val copy = when (state.stage) {
        SurveyRecoveryStage.AnswersIncomplete -> Res.string.survey_incomplete_title to Res.string.survey_incomplete_body
        SurveyRecoveryStage.Insufficient -> Res.string.survey_insufficient_title to Res.string.survey_insufficient_body
        SurveyRecoveryStage.RetestConfirmation -> Res.string.survey_retest_title to Res.string.survey_retest_body
        SurveyRecoveryStage.AnalysisVersionUnsupported -> Res.string.survey_version_title to Res.string.survey_version_body
        SurveyRecoveryStage.MigrationLoading -> Res.string.survey_migration_loading_title to Res.string.survey_migration_loading_body
        SurveyRecoveryStage.MigrationReview -> Res.string.survey_migration_review_title to Res.string.survey_migration_review_body
        SurveyRecoveryStage.Migrating -> Res.string.survey_migrating_title to Res.string.survey_migrating_body
        SurveyRecoveryStage.MigrationPolicyChanged -> Res.string.survey_policy_changed_title to Res.string.survey_policy_changed_body
        SurveyRecoveryStage.MigrationUnsupported -> Res.string.survey_migration_unsupported_title to Res.string.survey_migration_unsupported_body
        SurveyRecoveryStage.MigrationLoadFailed -> Res.string.survey_plan_failed_title to Res.string.survey_plan_failed_body
        SurveyRecoveryStage.MigrationOutcomeUnknown -> Res.string.survey_migration_unknown_title to Res.string.survey_migration_unknown_body
    }
    val action = state.primaryAction
    val button = when (action) {
        SurveyRecoveryAction.RefreshSession -> Res.string.survey_check_unanswered
        SurveyRecoveryAction.ReloadResult -> Res.string.survey_reload_result
        SurveyRecoveryAction.ReviewRetest -> Res.string.survey_review_retest
        is SurveyRecoveryAction.StartOrResumeRetest -> Res.string.survey_start_retest
        SurveyRecoveryAction.CheckCurrentSurvey -> Res.string.survey_check_current
        SurveyRecoveryAction.ReloadMigrationPlan -> Res.string.survey_reload_plan
        is SurveyRecoveryAction.ConfirmMigration -> Res.string.survey_confirm_migration
        is SurveyRecoveryAction.CheckMigrationOutcome -> Res.string.survey_check_migration
        else -> Res.string.survey_loading_recovery
    }
    SurveyFrame(modifier, footer = {
        AppButton(stringResource(button), Modifier.fillMaxWidth(), enabled = action != null, loading = state.busy,
            onClick = { if (!state.busy && action != null) onAction(action) })
    }) {
        Text(stringResource(copy.first), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
        Text(stringResource(copy.second), style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary)
        if (state.stage == SurveyRecoveryStage.Insufficient) {
            if (state.visibleMissing.isEmpty()) Text(stringResource(Res.string.survey_missing_unknown))
            else {
                Text(stringResource(Res.string.survey_missing_count, state.visibleMissing.size), style = MaterialTheme.typography.titleMedium)
                state.visibleMissing.forEach { q ->
                    val heading = q.ordinal?.takeIf { it > 0 }?.let { stringResource(Res.string.survey_missing_ordinal, it) }
                        ?: stringResource(Res.string.survey_missing_question)
                    Text(heading, style = MaterialTheme.typography.titleMedium)
                    Text(q.text?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.survey_missing_text_unknown))
                }
            }
        }
        if (state.stage == SurveyRecoveryStage.MigrationReview) {
            val plan = state.validMigration
            if (plan == null) Text(stringResource(Res.string.survey_plan_invalid), color = AppColors.Error)
            else {
                Text(stringResource(Res.string.survey_preserved_count, plan.preservedQuestionIds.size))
                Text(stringResource(Res.string.survey_reconfirm_count, plan.reconfirmQuestionIds.size))
                Text(stringResource(Res.string.survey_incompatible_count, plan.incompatibleQuestionIds.size))
                Text(stringResource(Res.string.survey_migration_existing), color = AppColors.TextSecondary)
            }
        }
        if (!state.busy) AppButton(stringResource(Res.string.survey_recovery_back), Modifier.fillMaxWidth(),
            variant = AppButtonVariant.Secondary, onClick = { onAction(SurveyRecoveryAction.Back) })
    }
}
