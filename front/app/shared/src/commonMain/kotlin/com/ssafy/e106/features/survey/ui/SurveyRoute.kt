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
    SurveyStepScreen(state, onReload, onStart, onBack, onSubmit, onRequestAnalysis, onRefreshAnalysis, onRefreshStage,
        onRetest, onVersion, onPreviousSession, onServerDraft)
}
