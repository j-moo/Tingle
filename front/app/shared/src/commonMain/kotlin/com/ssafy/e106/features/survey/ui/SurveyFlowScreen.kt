package com.ssafy.e106.features.survey.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.*
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
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
    SurveyFrame(modifier, onBack = onResumeLater, centered = true, footer = {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            AppButton(stringResource(button), modifier = Modifier.fillMaxWidth(),
                loading = busy, loadingText = stringResource(button), onClick = onPrimary)
            // 시안 01: 나가기는 왼쪽 위 < 하나(나중에 할게요 버튼 없음). 질문지 버전 · 이전 회차는 아래 흰 버튼(회색 테두리)으로
            onCheckVersion?.let { Minor(Res.string.survey_change_version, it) }
            onPreviousSession?.let { Minor(Res.string.survey_change_previous, it) }
        }
    }) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
        Text(stringResource(description), style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
        if (state.stage == SurveyFlowStage.Resume && state.total > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.survey_saved_count, state.savedCount, state.total) + " · " +
                    stringResource(Res.string.survey_remaining, (state.total - state.savedCount).coerceAtLeast(0)),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                LinearProgressIndicator({ state.savedCount.toFloat() / state.total }, Modifier.fillMaxWidth().height(6.dp),
                    color = AppColors.SelectionBorder, trackColor = AppColors.BorderSubtle, drawStopIndicator = {})
            }
        }
        if (state.hasPreviousResult && state.stage != SurveyFlowStage.Complete) {
            Text(stringResource(Res.string.survey_previous_result), style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary, textAlign = TextAlign.Center)
        }
    }
}
