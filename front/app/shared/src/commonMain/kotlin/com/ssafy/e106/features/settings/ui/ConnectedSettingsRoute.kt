package com.ssafy.e106.features.settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.onboarding.ui.OnboardingPanel
import com.ssafy.e106.features.preferences.ui.AgePreferenceScreen
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.features.settings.data.SettingsKind
import com.ssafy.e106.features.keyring.ui.KeyringSyncViewModel
import com.ssafy.e106.features.keyring.ui.KeyringSyncProgressList
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** 메뉴를 여는 것만으로 PUT 하지 않는다. 화면 복귀 시 GET으로 버전과 접근 권한을 다시 확인한다. */
@Composable fun ConnectedSettingsRoute(session: Session.SignedIn, onBack: () -> Unit,
    model: MatchingSettingsViewModel = koinViewModel(), showKeyringSync: Boolean = false) {
    val state by model.state.collectAsStateWithLifecycle()
    // 화면 재생성은 실제 나가기가 아니다. 선택 화면을 복원하고 ViewModel의 입력/저장을 유지한다.
    var selected by rememberSaveable(session) { mutableStateOf(false) }
    var selectedKind by rememberSaveable(session) { mutableStateOf(SettingsKind.AGE.name) }
    fun back() { model.leave(); selected = false }
    ProfileBackHandler(true) { if (selected) back() else onBack() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (selected) {
        if (state.kind.name != selectedKind) model.open(SettingsKind.valueOf(selectedKind)) else model.load()
    } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.background() }
    if (state.owner != session) return
    if (!selected) {
        OnboardingPanel(stringResource(Res.string.settings_api_entry)) {
            Text(stringResource(Res.string.settings_api_gate_help))
            SettingsKind.entries.forEach { kind -> AppButton(stringResource(if (kind == SettingsKind.AGE)
                Res.string.age_preference_title else Res.string.settings_matching_title), onClick = {
                selectedKind = kind.name; selected = true; model.open(kind)
            }) }
            AppButton(stringResource(Res.string.settings_api_refresh_stage), onClick = model::refreshStage, enabled = !state.busy)
            state.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onBack) { Text(stringResource(Res.string.settings_api_back)) }
        }
        return
    }
    state.conflict?.let { conflict ->
        SettingsConflictScreen(conflict) { action ->
            if (action == ConflictAction.Back) back() else model.conflictAction(action)
        }
        return
    }
    if (state.document == null) {
        OnboardingPanel(stringResource(if (state.kind == SettingsKind.AGE) Res.string.age_preference_title else Res.string.settings_matching_title)) {
            if (state.busy) CircularProgressIndicator()
            Text(stringResource(if (state.error is AppError.OnboardingRequired) Res.string.settings_api_blocked
                else if (state.error != null) Res.string.settings_load_failed else Res.string.settings_loading))
            state.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
            AppButton(stringResource(Res.string.settings_retry_load), onClick = model::load, enabled = !state.busy)
            TextButton(onClick = ::back) { Text(stringResource(Res.string.settings_api_back)) }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (state.saved) Text(stringResource(Res.string.settings_api_saved), modifier = Modifier
            .statusBarsPadding().padding(horizontal = AppSpacing.Large))
        if (state.kind == SettingsKind.AGE) AgePreferenceScreen(
            state.age.copy(saving = state.busy), model::editAge, model::save,
            modifier = Modifier.weight(1f), onBack = ::back, submitEnabled = state.canSave,
            submitLabel = stringResource(Res.string.settings_api_save))
        else OnboardingPanel(stringResource(Res.string.settings_matching_title)) {
            SettingsToggle(Res.string.settings_matching, Res.string.settings_matching_help,
                state.matching?.enabled, state.editable) { model.editMatching(enabled = it) }
            SettingsToggle(Res.string.settings_photo, Res.string.settings_photo_help,
                state.matching?.photoRequired, state.editable) { model.editMatching(photoRequired = it) }
            Text(stringResource(Res.string.settings_existing_help))
            Text(stringResource(Res.string.settings_api_sync_unconfirmed))
            // 서버 설정 저장과 보드 저장/ACK는 별개다. 개발 전경 연결의 실제 진행만 표시한다.
            if (showKeyringSync) {
                val sync: KeyringSyncViewModel = koinViewModel()
                val progress by sync.state.collectAsStateWithLifecycle()
                if (progress.owner == session.userId)
                    KeyringSyncProgressList(progress, {}, {}, showActions = false)
            }
            AppButton(stringResource(Res.string.settings_api_save), enabled = state.canSave,
                loading = state.saving, onClick = model::save)
            AppButton(stringResource(Res.string.settings_refresh), enabled = !state.busy,
                variant = AppButtonVariant.Secondary, onClick = model::load)
            TextButton(onClick = ::back) { Text(stringResource(Res.string.settings_api_back)) }
        }
    }
}
