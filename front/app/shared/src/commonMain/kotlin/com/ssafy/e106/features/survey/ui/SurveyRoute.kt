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
        onPreviousSession = model::openPrevious)
}

@Composable fun SurveyConnectedScreen(
    state: SurveyUiState, onReload: () -> Unit, onStart: () -> Unit, onResume: () -> Unit,
    onAnswer: (SurveyAnswer) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onReview: () -> Unit, onBack: () -> Unit,
    onSubmit: () -> Unit = {}, onRequestAnalysis: () -> Unit = {},
    onRefreshAnalysis: () -> Unit = {}, onRefreshStage: () -> Unit = {},
    onRetest: () -> Unit = {}, onVersion: () -> Unit = {}, onPreviousSession: () -> Unit = {},
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
        if (state.analysisResult?.status == AnalysisStatus.SUCCEEDED) Text(stringResource(Res.string.survey_api_stage_notice))
        if (state.stageUnchanged) Text(stringResource(Res.string.survey_api_stage_unchanged))
        if (state.exitRequested) Text(stringResource(Res.string.survey_api_leaving))
        state.stageError?.let { Text(it.message) }
        state.error?.let { Text(it.message) }
        if (state.recoveryRequired) Text(stringResource(Res.string.survey_api_recovery))
    }
}
