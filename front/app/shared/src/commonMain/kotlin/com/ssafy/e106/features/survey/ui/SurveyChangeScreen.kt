package com.ssafy.e106.features.survey.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.features.survey.data.SurveyChangeKind
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable fun SurveyChangeRoute(sourceId: Long, kind: SurveyChangeKind, onClose: () -> Unit,
    model: SurveyChangeViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.attach(sourceId, kind) }
    val currentClose by rememberUpdatedState(onClose)
    // 완료 이동은 새로 그려진 화면이 소비한다. 회전·홈 이동으로 전환 요청을 끊지 않는다.
    // 시스템 뒤로가기도 전환 결과를 기다린다. 이동 의도를 ViewModel에 두어 회전 뒤 한 번만 닫는다.
    val exit: () -> Unit = model::requestLeave
    LaunchedEffect(state.page, state.busy, state.exitRequested) {
        if ((state.page == SurveyChangePage.Done || state.exitRequested) && !state.busy) {
            model.leave(); currentClose()
        }
    }
    ProfileBackHandler(true, exit)
    SurveyChangeScreen(state, model::confirm, model::reload, exit)
}

@Composable fun SurveyChangeScreen(state: SurveyChangeState, onConfirm: () -> Unit, onReload: () -> Unit, onBack: () -> Unit) {
    SurveyFrame(footer = {
        Column(verticalArrangement = Arrangement.spacedBy(com.ssafy.e106.core.ui.theme.AppSpacing.Small)) {
            when {
                state.busy || state.page == SurveyChangePage.Loading -> AppButton(stringResource(Res.string.survey_api_loading), loading = true, onClick = {})
                state.page in setOf(SurveyChangePage.Retest, SurveyChangePage.Migration, SurveyChangePage.Uncertain) ->
                    AppButton(stringResource(when(state.page) {
                        SurveyChangePage.Retest -> Res.string.survey_change_begin
                        SurveyChangePage.Migration -> Res.string.survey_change_move
                        else -> Res.string.survey_change_recover
                    }), modifier = Modifier.fillMaxWidth(), onClick = onConfirm)
                state.page in setOf(SurveyChangePage.Error, SurveyChangePage.PolicyChanged) ->
                    AppButton(stringResource(Res.string.survey_change_reload), onClick = onReload)
                else -> Unit
            }
            if (!state.busy) AppButton(stringResource(Res.string.survey_api_back), variant = AppButtonVariant.Secondary, onClick = onBack)
        }
    }) {
        Text(stringResource(when(state.page) {
            SurveyChangePage.Retest -> Res.string.survey_change_retest_body
            SurveyChangePage.Migration -> Res.string.survey_change_migration_body
            SurveyChangePage.Current -> Res.string.survey_change_current
            SurveyChangePage.Unsupported -> Res.string.survey_change_unsupported
            SurveyChangePage.PolicyChanged -> Res.string.survey_change_changed
            SurveyChangePage.Uncertain -> Res.string.survey_change_unknown
            SurveyChangePage.Error -> Res.string.survey_api_error
            else -> Res.string.survey_api_loading
        }))
        state.plan?.let {
            Text(stringResource(Res.string.survey_change_counts, it.preservedQuestionIds.size,
                it.reconfirmQuestionIds.size, it.incompatibleQuestionIds.size))
        }
        state.error?.let { Text(it.message) }
    }
}
