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

/** Only a display of caller-owned state, not a submission/calculation implementation. */
@Composable
fun SurveyFlowScreen(
    state: SurveyFlowState,
    onPrimary: () -> Unit,
    onResumeLater: () -> Unit,
    onRecoveryAction: (SurveyRecoveryAction) -> Unit,
    modifier: Modifier = Modifier,
    onCheckVersion: (() -> Unit)? = null,
    onPreviousSession: (() -> Unit)? = null,
) {
    state.recovery?.let { SurveyRecoveryScreen(it, onRecoveryAction, modifier); return }
    val busy = state.stage == SurveyFlowStage.Submitting || state.stage == SurveyFlowStage.Calculating
    val title = when (state.stage) {
        SurveyFlowStage.Resume -> Res.string.survey_resume_title
        SurveyFlowStage.ReadyToSubmit -> Res.string.survey_ready_title
        SurveyFlowStage.Submitting -> Res.string.survey_submitting_title
        SurveyFlowStage.SubmitFailed -> Res.string.survey_submit_failed_title
        SurveyFlowStage.Calculating -> Res.string.survey_calculating_title
        SurveyFlowStage.CalculationFailed -> Res.string.survey_calculation_failed_title
        SurveyFlowStage.Complete -> Res.string.survey_complete_title
    }
    val description = when (state.stage) {
        SurveyFlowStage.Resume -> if (state.savedCount == state.total) Res.string.survey_resume_all else Res.string.survey_resume_body
        SurveyFlowStage.ReadyToSubmit -> Res.string.survey_ready_body
        SurveyFlowStage.Submitting -> Res.string.survey_submitting_body
        SurveyFlowStage.SubmitFailed -> Res.string.survey_submit_failed_body
        SurveyFlowStage.Calculating -> Res.string.survey_calculating_body
        SurveyFlowStage.CalculationFailed -> Res.string.survey_calculation_failed_body
        SurveyFlowStage.Complete -> Res.string.survey_complete_body
    }
    val button = when (state.stage) {
        SurveyFlowStage.Resume -> Res.string.survey_resume_yes
        SurveyFlowStage.ReadyToSubmit -> Res.string.survey_submit
        SurveyFlowStage.Submitting -> Res.string.survey_submitting
        SurveyFlowStage.SubmitFailed -> Res.string.survey_retry_submit
        SurveyFlowStage.Calculating -> Res.string.survey_calculating
        SurveyFlowStage.CalculationFailed -> Res.string.survey_retry_calculation
        SurveyFlowStage.Complete -> Res.string.survey_continue
    }
    SurveyFrame(modifier, footer = {
        AppButton(stringResource(button), modifier = Modifier.fillMaxWidth(),
            loading = busy, loadingText = stringResource(button), onClick = onPrimary)
    }) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
        Text(stringResource(description), style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary)
        if (state.stage == SurveyFlowStage.Resume) {
            Text(stringResource(Res.string.survey_saved_count, state.savedCount, state.total),
                style = MaterialTheme.typography.titleMedium, color = AppColors.OnSelection)
            AppButton(stringResource(Res.string.survey_resume_no), variant = AppButtonVariant.Secondary,
                onClick = onResumeLater, modifier = Modifier.fillMaxWidth())
        }
        onCheckVersion?.let { AppButton(stringResource(Res.string.survey_change_version), variant = AppButtonVariant.Secondary, onClick = it) }
        onPreviousSession?.let { AppButton(stringResource(Res.string.survey_change_previous), variant = AppButtonVariant.Secondary, onClick = it) }
        if (state.hasPreviousResult && state.stage != SurveyFlowStage.Complete) {
            Text(stringResource(Res.string.survey_previous_result), style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary)
        }
    }
}
