package com.ssafy.e106.features.survey.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.features.survey.data.AnalysisStatus
import com.ssafy.e106.features.survey.data.SurveyChangeKind
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable fun SurveyRoute(session: Session.SignedIn, onBack: () -> Unit, model: SurveyViewModel = koinViewModel(),
    changeModel: SurveyChangeViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    // 회전으로 선택한 전환 화면이 닫히지 않도록 작은 식별값만 저장한다. 답변은 저장하지 않는다.
    var changeId by rememberSaveable(session.userId, session.status) { mutableStateOf<Long?>(null) }
    var changeKind by rememberSaveable(session.userId, session.status) { mutableStateOf(SurveyChangeKind.RETEST) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (changeId == null) model.attach() }
    // 백그라운드·회전은 화면 이탈이 아니다. 사용자가 돌아가기를 누를 때만 요청을 정리한다.
    // 뒤로가기는 요청 완료 후 소비한다. 회전 후에도 새 화면이 한 번만 이동한다.
    val currentBack by rememberUpdatedState(onBack)
    val exit: () -> Unit = model::requestLeave
    LaunchedEffect(state.exitRequested, state.busy) {
        if (state.exitRequested && !state.busy) { model.leave(); currentBack() }
    }
    changeId?.let { id ->
        SurveyChangeRoute(id, changeKind, model = changeModel, onClose = {
            changeId = null; model.finishChange()
        })
        return
    }
    ProfileBackHandler(true, exit)
    if (state.owner != session) return
    SurveyConnectedScreen(state, model::load, model::start, model::resume, model::answer,
        model::previous, model::next, model::reviewAnswers, exit,
        model::submit, model::requestAnalysis, model::refreshAnalysis, model::refreshStage,
        onRetest = { (state.pendingSourceId ?: state.session?.sessionId)?.let {
            if (model.beginChange()) { changeKind = SurveyChangeKind.RETEST; changeId = it }
        } },
        onVersion = { state.session?.let {
            if (model.beginChange()) { changeKind = SurveyChangeKind.MIGRATION; changeId = it.sessionId }
        } },
        onPreviousSession = model::openPrevious, onServerDraft = model::openServerDraft)
}

@Composable fun SurveyConnectedScreen(
    state: SurveyUiState, onReload: () -> Unit, onStart: () -> Unit, onResume: () -> Unit,
    onAnswer: (SurveyAnswer) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onReview: () -> Unit, onBack: () -> Unit,
    onSubmit: () -> Unit = {}, onRequestAnalysis: () -> Unit = {},
    onRefreshAnalysis: () -> Unit = {}, onRefreshStage: () -> Unit = {},
    onRetest: () -> Unit = {}, onVersion: () -> Unit = {}, onPreviousSession: () -> Unit = {},
    onServerDraft: () -> Unit = {},
) {
    if (state.pendingChange && !state.busy) {
        SurveyFrame(footer = {
            AppButton(stringResource(Res.string.survey_change_recover), onClick = onRetest)
            AppButton(stringResource(Res.string.survey_api_back), onClick = onBack)
        }) { Text(stringResource(Res.string.survey_change_unknown)) }
        return
    }
    if (state.page == SurveyPage.Question && !state.recoveryRequired && state.error == null) {
        state.questionScreen?.let { SurveyQuestionScreen(it, onAnswer, onPrevious, onNext, onReload,
            busy = state.busy || state.changingSession || state.exitRequested,
            notice = when {
                state.exitRequested -> stringResource(Res.string.survey_api_leaving)
                state.returnedToMissing -> stringResource(Res.string.survey_api_returned_missing)
                else -> null
            },
            onCheckVersion = onVersion, onPreviousSession = onPreviousSession.takeIf { state.previousBookmark != null }) }
        return
    }
    if (state.page == SurveyPage.Resume && !state.busy && state.error == null) {
        SurveyFlowScreen(SurveyFlowState(SurveyFlowStage.Resume, state.session!!.answeredCount, state.session.questionCount),
            onResume, onBack, {}, onCheckVersion = onVersion,
            onPreviousSession = onPreviousSession.takeIf { state.previousBookmark != null })
        return
    }
    SurveyFrame(footer = {
        Column {
            when {
                state.error != null -> AppButton(stringResource(Res.string.survey_api_reload), loading = state.busy, onClick = onReload)
                state.busy || state.page == SurveyPage.Loading -> AppButton(stringResource(Res.string.survey_api_loading), loading = true, onClick = {})
                state.page == SurveyPage.Intro -> AppButton(stringResource(Res.string.survey_api_start), onClick = onStart)
                state.page == SurveyPage.Review -> {
                    AppButton(stringResource(Res.string.survey_api_submit), onClick = onSubmit)
                    AppButton(stringResource(Res.string.survey_api_review), variant = AppButtonVariant.Secondary, onClick = onReview)
                }
                state.page == SurveyPage.Completed -> when {
                    state.analysisReference == null || state.analysisResult?.status == AnalysisStatus.FAILED ->
                        AppButton(stringResource(Res.string.survey_api_analyze), onClick = onRequestAnalysis)
                    state.analysisResult?.status == AnalysisStatus.SUCCEEDED ->
                        AppButton(stringResource(Res.string.survey_api_stage), onClick = onRefreshStage)
                    else -> AppButton(stringResource(Res.string.survey_api_analysis_refresh), onClick = onRefreshAnalysis)
                }
                else -> Unit
            }
            if (!state.busy && state.session != null) {
                // 계산 중인 제출 회차를 복구하더라도 서버에 남아 있는 작성 중 회차로 이동할 수 있다.
                if (state.page == SurveyPage.Completed && state.serverDraftId != null && !state.recoveryRequired)
                    AppButton(stringResource(Res.string.survey_api_server_draft), variant = AppButtonVariant.Secondary, onClick = onServerDraft)
                if (state.page == SurveyPage.Completed) AppButton(stringResource(Res.string.survey_change_retest),
                    variant = AppButtonVariant.Secondary, onClick = onRetest)
                AppButton(stringResource(Res.string.survey_change_version), variant = AppButtonVariant.Secondary, onClick = onVersion)
            }
            if (!state.busy && state.previousBookmark != null) AppButton(stringResource(Res.string.survey_change_previous),
                variant = AppButtonVariant.Secondary, onClick = onPreviousSession)
            if (!state.busy) AppButton(stringResource(Res.string.survey_api_back), variant = AppButtonVariant.Secondary, onClick = onBack)
        }
    }) {
        Text(stringResource(when {
            state.error != null -> Res.string.survey_api_error
            state.page == SurveyPage.Intro -> Res.string.survey_api_intro
            state.page == SurveyPage.Review -> Res.string.survey_api_all_saved
            state.page == SurveyPage.Completed -> when (state.analysisResult?.status) {
                AnalysisStatus.PENDING -> Res.string.survey_api_pending
                AnalysisStatus.SUCCEEDED -> Res.string.survey_api_succeeded
                AnalysisStatus.INSUFFICIENT -> Res.string.survey_api_insufficient
                AnalysisStatus.FAILED -> Res.string.survey_api_failed
                null -> Res.string.survey_api_completed
            }
            else -> Res.string.survey_api_loading
        }))
        if (state.page == SurveyPage.Intro && state.definition != null) {
            Text(stringResource(Res.string.survey_api_count, state.definition.questionCount))
        }
        if (state.page == SurveyPage.Completed && state.analysisResult?.status == AnalysisStatus.INSUFFICIENT) {
            val missing = state.analysisResult.missingQuestionIds.toSet()
            val ordinals = state.definition?.questions?.filter { it.questionId in missing }?.map { it.ordinal }.orEmpty()
            if (ordinals.isNotEmpty()) Text(stringResource(Res.string.survey_api_missing, ordinals.joinToString(", ")))
        }
        // 마이에서 결과를 다시 연 가입 완료 계정에는 남은 가입 준비가 있다고 안내하지 않는다.
        // 분석 성공과 서비스 이용 가능 여부는 별개이며, 인증 기반의 서버 판정(isReady)을 그대로 따른다.
        val signupReady = state.owner?.isReady == true
        if (state.analysisResult?.status == AnalysisStatus.SUCCEEDED && !signupReady)
            Text(stringResource(Res.string.survey_api_stage_notice))
        if (state.analysisResult?.status == AnalysisStatus.SUCCEEDED && state.analysisApplied == false)
            Text(stringResource(Res.string.survey_api_application_pending))
        // 조회 성공 후 같은 화면에 남아 있어도 READY는 정상 완료다. BLE 매칭 준비 완료를 뜻하지는 않는다.
        if (state.stageUnchanged) Text(stringResource(if (signupReady)
            Res.string.survey_api_signup_ready else Res.string.survey_api_stage_unchanged))
        if (state.exitRequested) Text(stringResource(Res.string.survey_api_leaving))
        state.stageError?.let { Text(it.message) }
        state.error?.let { Text(it.message) }
        if (state.recoveryRequired) Text(stringResource(Res.string.survey_api_recovery))
    }
}
