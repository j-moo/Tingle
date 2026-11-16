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
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.profile.ui.pullRefresh
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

/** 상태 화면 모양(동그라미 아이콘 + 짧은 제목)으로 보여 줄 단계의 종류. null 이면 제목 · 설명만 있는 보통 단계다 */
internal fun SurveyUiState.stepKind(): StateKind? = when (step()) {
    SurveyStep.Error -> if (error == null) StateKind.Unconfirmed else error.stateKind()   // 오류 없이 여기 왔다면 답 저장 결과를 확인하지 못한 것
    SurveyStep.Failed -> StateKind.Server
    SurveyStep.Insufficient -> StateKind.Unknown
    SurveyStep.Succeeded -> StateKind.Done
    else -> null
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
    val kind = state.stepKind()
    val count = state.definition?.questionCount ?: state.session?.questionCount
    // 마이에서 결과를 다시 연 가입 완료 계정은 남은 가입 단계가 없으니 "다음 단계로" 대신 돌아가기.
    // 분석 성공과 서비스 이용 가능 여부는 별개이며, 인증 기반의 서버 판정(isReady)을 그대로 따른다.
    val signupReady = state.owner?.isReady == true
    val retry = Res.string.state_retry
    // 제목 · 설명 · 버튼(문구, 동작). 버튼이 null 이면 불러오는 중이라 뼈대만 둔다
    val (title, body, primary) = when (step) {
        // 불러오기 실패는 "다시 시도", 답 저장 결과를 확인하지 못했으면 다시 보내지 않고 "다시 확인"
        SurveyStep.Error -> Triple(Res.string.survey_step_unconfirmed_title, null, (if (state.recoveryRequired) Res.string.survey_step_recheck else retry) to onReload)
        SurveyStep.Loading -> Triple(Res.string.state_loading, null, null)
        SurveyStep.Intro -> Triple(Res.string.survey_step_intro_title, Res.string.survey_step_intro_body, Res.string.survey_step_start to onStart)
        SurveyStep.Done -> Triple(if (count != null) Res.string.survey_step_done_title else Res.string.survey_ready_title,
            Res.string.survey_step_done_body, Res.string.survey_step_submit to onSubmit)
        SurveyStep.Submitted -> Triple(Res.string.survey_step_submitted_title, Res.string.survey_step_submitted_body, Res.string.survey_step_analyze to onRequestAnalysis)
        SurveyStep.Failed -> Triple(Res.string.survey_step_failed_title, Res.string.survey_step_failed_body, retry to onRequestAnalysis)
        SurveyStep.Succeeded -> Triple(Res.string.survey_step_succeeded_title, Res.string.survey_step_succeeded_body,
            if (signupReady) Res.string.survey_api_back to onBack else Res.string.survey_step_next to onRefreshStage)
        SurveyStep.Insufficient -> Triple(Res.string.survey_step_insufficient_title, Res.string.survey_step_insufficient_body, Res.string.survey_step_check to onRefreshAnalysis)
        SurveyStep.Pending -> Triple(Res.string.survey_step_pending_title, Res.string.survey_step_pending_body, Res.string.survey_step_check to onRefreshAnalysis)
    }
    // 가입 단계 확인("다음 단계로")에 실패하면 버튼 바로 위 한 줄로 알리고 버튼은 "다시 시도"가 된다
    val stageError = state.stageError?.takeIf { !state.busy }
    // 당겨서 새로고침은 아래 버튼과 같은 일을 하는 단계에만 붙인다(불러오기 실패 · 분석 상태 확인)
    val reload: (() -> Unit)? = when (step) {
        SurveyStep.Error -> onReload
        SurveyStep.Pending, SurveyStep.Insufficient -> onRefreshAnalysis
        else -> null
    }
    val (refreshing, pull) = pullRefresh(state.busy, reload ?: {})
    SurveyFrame(onBack = onBack, centered = true, refreshing = refreshing, onRefresh = pull.takeIf { reload != null }, footer = {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            // 불러오는 중엔 어떤 버튼이 올지 모르므로 버튼 자리만 회색으로 둔다
            if (primary == null) SkeletonBone(height = AppSpacing.ControlMinHeight, shape = AppShapes.Control)
            stageError?.let { InlineError(it.message) }
            // 보내지 못한 답이 남아 있으면 제출만 기다린다(뒤에서 계속 다시 보내는 중)
            val waiting = step == SurveyStep.Done && state.pending.isNotEmpty()
            // 분석 중 화면은 가운데에 로딩 모션이 늘 돌아서 버튼에는 로딩 원을 두지 않는다(확인하는 동안은 눌리지만 않게)
            val analyzing = step == SurveyStep.Pending
            if (primary != null) AppButton(stringResource(if (stageError != null) retry else primary.first), Modifier.fillMaxWidth(),
                loading = state.busy && !analyzing, enabled = !waiting && !(analyzing && state.busy),
                loadingText = stringResource(primary.first), onClick = primary.second)
            // 제출한 뒤에는 "재검사하기" 하나만 흰 버튼으로 둔다. 나가기는 왼쪽 위 <
            // (작성 중 회차 이어하기는 재검사 안내 화면이 이어 주고, 질문지 버전 · 직전 회차 확인 버튼은 두지 않는다)
            if (!state.busy && state.session != null && state.page == SurveyPage.Completed) Minor(Res.string.survey_change_retest, onRetest)
        }
    }) {
        if (step == SurveyStep.Loading) SkeletonGroup {
            // 불러오는 중: 가운데 로딩 원 대신 제목 · 설명 자리를 회색으로 둔다
            SkeletonBone(Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(0.7f), height = 28.dp)
            SkeletonBone(Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(0.5f))
        } else {
            val text = when {
                step == SurveyStep.Error && state.error != null -> state.error.stateTitle()
                step == SurveyStep.Done && count != null -> stringResource(title, count)
                else -> stringResource(title)
            }
            // 분석 중에는 제목 위에 로딩 모션을 계속 보여 준다
            if (step == SurveyStep.Pending) CircularProgressIndicator(Modifier.size(40.dp), color = AppColors.TextPrimary, strokeWidth = 3.dp)
            // 실패 · 부족 · 완료는 상태 화면 모양(아이콘 + 제목), 그 밖의 단계는 큰 제목
            if (kind != null) StateView(kind, text)
            else Text(text, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
            body?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary, textAlign = TextAlign.Center) }
        }
        if (step == SurveyStep.Done && state.pending.isNotEmpty()) Notice(stringResource(Res.string.survey_pending_submit, state.pending.size))
        if (step == SurveyStep.Intro) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                count?.let { Fact(stringResource(Res.string.survey_step_count, it)) }
                Fact(stringResource(Res.string.onboarding_hub_fact_15min))
                Fact(stringResource(Res.string.survey_step_resumable))
            }
        }
        if (step == SurveyStep.Insufficient) {
            val missing = state.analysisResult?.missingQuestionIds.orEmpty().toSet()
            val ordinals = state.definition?.questions?.filter { it.questionId in missing }?.map { it.ordinal }.orEmpty()
            if (ordinals.isNotEmpty()) Notice(stringResource(Res.string.survey_api_missing, ordinals.joinToString(", ")))
        }
        if (step == SurveyStep.Succeeded && state.analysisApplied == false) Notice(stringResource(Res.string.survey_api_application_pending))
        // 조회 성공 후 같은 화면에 남아 있어도 READY는 정상 완료다. BLE 매칭 준비 완료를 뜻하지는 않는다.
        if (state.stageUnchanged) Notice(stringResource(if (signupReady) Res.string.survey_api_signup_ready else Res.string.survey_api_stage_unchanged))
        if (state.exitRequested) Notice(stringResource(Res.string.survey_api_leaving))
        // 저장 여부를 모르는 채로 다음 답을 보내지 않는다는 안내는 줄이지 않는다
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
