package com.ssafy.e106.features.survey.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.features.profile.ui.pullRefresh
import com.ssafy.e106.features.survey.data.SurveyChangeKind
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
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
    // 아래 큰 버튼(문구, 동작). 결과를 확인해야 하면 "다시 확인", 불러오기 실패는 "다시 시도"
    val action: Pair<StringResource, () -> Unit>? = when (state.page) {
        SurveyChangePage.Retest -> Res.string.survey_change_begin to onConfirm
        SurveyChangePage.Migration -> Res.string.survey_change_move to onConfirm
        SurveyChangePage.Uncertain -> Res.string.survey_step_recheck to onConfirm
        SurveyChangePage.PolicyChanged -> Res.string.survey_step_recheck to onReload
        SurveyChangePage.Error -> Res.string.state_retry to onReload
        else -> null
    }
    val loading = state.page == SurveyChangePage.Loading || state.page == SurveyChangePage.Done
    val reloadable = state.page == SurveyChangePage.Error || state.page == SurveyChangePage.PolicyChanged
    val (refreshing, pull) = pullRefresh(state.busy, onReload)
    SurveyFrame(refreshing = refreshing, onRefresh = pull.takeIf { reloadable }, footer = {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            // 불러오는 중엔 어떤 버튼이 올지 모르므로 버튼 자리만 회색으로 둔다
            if (loading) SkeletonBone(height = AppSpacing.ControlMinHeight, shape = AppShapes.Control)
            else action?.let { AppButton(stringResource(it.first), Modifier.fillMaxWidth(), loading = state.busy, onClick = it.second) }
            if (!state.busy) AppButton(stringResource(Res.string.survey_api_back), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary, onClick = onBack)
        }
    }) {
        // 안내 문구는 줄이지 않는다(기존 회차 · 답변이 어떻게 되는지, 같은 대상으로 다시 요청한다는 설명)
        when (state.page) {
            SurveyChangePage.Retest -> Text(stringResource(Res.string.survey_change_retest_body))
            SurveyChangePage.Migration -> Text(stringResource(Res.string.survey_change_migration_body))
            SurveyChangePage.Current -> Text(stringResource(Res.string.survey_change_current))
            SurveyChangePage.Unsupported -> { StateView(StateKind.Locked, stringResource(Res.string.survey_migration_unsupported_title)); Info(Res.string.survey_change_unsupported) }
            SurveyChangePage.PolicyChanged -> { StateView(StateKind.Changed, stringResource(Res.string.survey_policy_changed_title)); Info(Res.string.survey_change_changed) }
            SurveyChangePage.Uncertain -> { StateView(StateKind.Unconfirmed, stringResource(Res.string.survey_migration_unknown_title)); Info(Res.string.survey_change_unknown) }
            SurveyChangePage.Error -> StateView(state.error.stateKind(), state.error.stateTitle())
            else -> SkeletonGroup { SkeletonBone(); SkeletonBone(); SkeletonBone(Modifier.fillMaxWidth(0.6f)) }
        }
        state.plan?.let {
            Text(stringResource(Res.string.survey_change_counts, it.preservedQuestionIds.size,
                it.reconfirmQuestionIds.size, it.incompatibleQuestionIds.size))
        }
    }
}

@Composable private fun Info(text: StringResource) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
}
