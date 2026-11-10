package com.ssafy.e106.features.survey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.survey.data.AnalysisStatus
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** 문항 밖 화면에서 지금 보여 줄 단계. 이어하기 · 문항 화면은 따로 그리므로, 그 페이지로 여기 왔다면 불러오는(바쁜) 중이다 */
internal enum class SurveyStep { Error, Loading, Intro, Done, Submitted, Pending, Succeeded, Insufficient, Failed }
internal fun SurveyUiState.step(): SurveyStep = when {
    error != null || (page == SurveyPage.Question && recoveryRequired) -> SurveyStep.Error
    page == SurveyPage.Loading || page == SurveyPage.Resume || page == SurveyPage.Question -> SurveyStep.Loading
    page == SurveyPage.Intro -> SurveyStep.Intro
    page == SurveyPage.Review -> SurveyStep.Done
    analysisReference == null -> SurveyStep.Submitted
    else -> when (analysisResult?.status) {
        AnalysisStatus.FAILED -> SurveyStep.Failed
        AnalysisStatus.SUCCEEDED -> SurveyStep.Succeeded
        AnalysisStatus.INSUFFICIENT -> SurveyStep.Insufficient
        AnalysisStatus.PENDING, null -> SurveyStep.Pending   // 결과를 아직 못 받았으면 분석 중으로 보고 상태 확인을 권한다
    }
}

/**
 * 성향테스트 문항 밖 화면(시안 01 기본 정리): 제목 · 설명 · 정보 · 아래 검정 버튼.
 * 시작 안내 · 다 답함(제출) · 제출 뒤 분석(요청 전 · 중 · 완료 · 부족 · 실패) · 불러오기. 흐름과 버튼 동작은 [SurveyViewModel] 그대로다.
 */
@Composable
internal fun SurveyStepScreen(
    state: SurveyUiState, onReload: () -> Unit, onStart: () -> Unit, onBack: () -> Unit,
    onSubmit: () -> Unit, onRequestAnalysis: () -> Unit,
    onRefreshAnalysis: () -> Unit, onRefreshStage: () -> Unit,
    onRetest: () -> Unit, onVersion: () -> Unit, onPreviousSession: () -> Unit, onServerDraft: () -> Unit,
) {
    val step = state.step()
    val count = state.definition?.questionCount ?: state.session?.questionCount
    // 마이에서 결과를 다시 연 가입 완료 계정은 남은 가입 단계가 없으니 "다음 단계로" 대신 돌아가기.
    // 분석 성공과 서비스 이용 가능 여부는 별개이며, 인증 기반의 서버 판정(isReady)을 그대로 따른다.
    val signupReady = state.owner?.isReady == true
    // 제목 · 설명 · 버튼(문구, 동작). 버튼이 null 이면 불러오는 중이라 가운데 로딩만 둔다
    val (title, body, primary) = when (step) {
        SurveyStep.Error -> Triple(Res.string.survey_step_error_title, null, Res.string.survey_step_reload to onReload)
        SurveyStep.Loading -> Triple(Res.string.survey_step_loading_title, null, null)
        SurveyStep.Intro -> Triple(Res.string.survey_step_intro_title, Res.string.survey_step_intro_body, Res.string.survey_step_start to onStart)
        SurveyStep.Done -> Triple(if (count != null) Res.string.survey_step_done_title else Res.string.survey_ready_title,
            Res.string.survey_step_done_body, Res.string.survey_step_submit to onSubmit)
        SurveyStep.Submitted -> Triple(Res.string.survey_step_submitted_title, Res.string.survey_step_submitted_body, Res.string.survey_step_analyze to onRequestAnalysis)
        SurveyStep.Failed -> Triple(Res.string.survey_step_failed_title, Res.string.survey_step_failed_body, Res.string.survey_step_retry_analysis to onRequestAnalysis)
        SurveyStep.Succeeded -> Triple(Res.string.survey_step_succeeded_title, Res.string.survey_step_succeeded_body,
            if (signupReady) Res.string.survey_api_back to onBack else Res.string.survey_step_next to onRefreshStage)
        SurveyStep.Insufficient -> Triple(Res.string.survey_step_insufficient_title, Res.string.survey_step_insufficient_body, Res.string.survey_step_check to onRefreshAnalysis)
        SurveyStep.Pending -> Triple(Res.string.survey_step_pending_title, Res.string.survey_step_pending_body, Res.string.survey_step_check to onRefreshAnalysis)
    }
    SurveyFrame(onBack = onBack, centered = true, footer = {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            // 불러오는 중엔 가운데 로딩 하나만 둔다(아래 버튼 자리의 로딩은 두지 않음)
            if (primary != null) AppButton(stringResource(primary.first), Modifier.fillMaxWidth(), loading = state.busy,
                loadingText = stringResource(primary.first), onClick = primary.second)
            // 자주 쓰지 않는 길(작성 중 회차 · 다시 하기 · 질문지 버전 · 이전 회차)은 회색 테두리 흰 버튼으로. 나가기는 왼쪽 위 <
            if (!state.busy && state.session != null) {
                if (state.page == SurveyPage.Completed && state.serverDraftId != null && !state.recoveryRequired)
                    Minor(Res.string.survey_api_server_draft, onServerDraft)
                if (state.page == SurveyPage.Completed) Minor(Res.string.survey_change_retest, onRetest)
                Minor(Res.string.survey_change_version, onVersion)
            }
            if (!state.busy && state.previousBookmark != null) Minor(Res.string.survey_change_previous, onPreviousSession)
        }
    }) {
        Text(if (step == SurveyStep.Done && count != null) stringResource(title, count) else stringResource(title),
            style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
        body?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary, textAlign = TextAlign.Center) }
        if (step == SurveyStep.Intro) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                count?.let { Fact(stringResource(Res.string.survey_step_count, it)) }
                Fact(stringResource(Res.string.onboarding_hub_fact_15min))
                Fact(stringResource(Res.string.survey_step_resumable))
            }
        }
        if (step == SurveyStep.Loading || step == SurveyStep.Pending)
            CircularProgressIndicator(Modifier.size(32.dp), color = AppColors.TextPrimary, strokeWidth = 3.dp)
        if (step == SurveyStep.Insufficient) {
            val missing = state.analysisResult?.missingQuestionIds.orEmpty().toSet()
            val ordinals = state.definition?.questions?.filter { it.questionId in missing }?.map { it.ordinal }.orEmpty()
            if (ordinals.isNotEmpty()) Notice(stringResource(Res.string.survey_api_missing, ordinals.joinToString(", ")))
        }
        if (step == SurveyStep.Succeeded && state.analysisApplied == false) Notice(stringResource(Res.string.survey_api_application_pending))
        // 조회 성공 후 같은 화면에 남아 있어도 READY는 정상 완료다. BLE 매칭 준비 완료를 뜻하지는 않는다.
        if (state.stageUnchanged) Notice(stringResource(if (signupReady) Res.string.survey_api_signup_ready else Res.string.survey_api_stage_unchanged))
        if (state.exitRequested) Notice(stringResource(Res.string.survey_api_leaving))
        state.stageError?.let { Notice(it.message, error = true) }
        state.error?.let { Notice(it.message, error = true) }
        if (state.recoveryRequired) Notice(stringResource(Res.string.survey_api_recovery))
    }
}

@Composable private fun Fact(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(AppColors.SurfaceMuted).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable private fun Notice(text: String, error: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (error) AppColors.Error else AppColors.TextSecondary, textAlign = TextAlign.Center)
}

/** 흰 버튼(회색 테두리) */
@Composable internal fun Minor(text: StringResource, onClick: () -> Unit) {
    AppButton(stringResource(text), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary, onClick = onClick)
}
