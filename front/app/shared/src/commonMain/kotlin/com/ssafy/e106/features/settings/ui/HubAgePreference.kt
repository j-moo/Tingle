package com.ssafy.e106.features.settings.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.components.AppButtonVariant
import com.ssafy.e106.core.ui.components.InlineError
import com.ssafy.e106.core.ui.components.SkeletonBone
import com.ssafy.e106.core.ui.components.SkeletonGroup
import com.ssafy.e106.core.ui.components.stateTitle
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSpacing
import androidx.compose.ui.unit.dp
import com.ssafy.e106.features.preferences.ui.AgePreferenceFields
import com.ssafy.e106.features.settings.data.SettingsKind
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * 가입 준비 화면의 "희망 나이 · 매칭 설정" 줄 안에 펼쳐 넣는 희망 나이 입력.
 * 새 화면으로 들어가지 않고 그 자리에서 고르고 저장한다. 저장하면 가입 단계가 다음으로 넘어간다.
 * 다른 변경과 겹쳐 비교가 필요한 경우(충돌)만 [onOpenFull] 로 전체 화면을 연다.
 */
@Composable
fun ColumnScope.HubAgePreference(session: Session.SignedIn, onOpenFull: () -> Unit, model: MatchingSettingsViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    // 들어올 때마다 서버 값을 다시 받는다(메뉴를 여는 것만으로 저장하지는 않는다)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (state.kind != SettingsKind.AGE || state.document == null) model.open(SettingsKind.AGE) else model.load() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.background() }
    if (state.owner != session) return
    // 저장이 끝나면(또는 서버에 이미 저장돼 있으면) 가입 단계를 다시 확인해 다음 단계로 넘긴다. 실패하면 버튼으로 다시 시도한다
    var asked by remember(session) { mutableStateOf(false) }
    LaunchedEffect(state.saved, state.settled, state.busy) {
        if ((state.saved || state.settled) && !state.busy && !asked) { asked = true; model.refreshStage() }
    }
    when {
        // 저장 결과 확인 · 다른 변경과 겹침: 비교 화면이 필요해 전체 화면으로 보낸다
        state.conflict != null -> {
            InlineError(stringResource(Res.string.hub_age_conflict))
            AppButton(stringResource(Res.string.hub_age_open), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary, onClick = onOpenFull)
        }
        // 못 불러옴: 한 줄로 알리고 다시 시도
        state.document == null && state.error != null -> {
            InlineError(state.error.stateTitle())
            AppButton(stringResource(Res.string.state_retry), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary, enabled = !state.busy, onClick = model::load)
        }
        // 불러오는 중: 탭 · 막대 · 버튼 자리를 회색으로
        state.document == null -> SkeletonGroup(spacing = AppSpacing.Medium) {
            SkeletonBone(height = AppSpacing.ControlMinHeight, shape = AppShapes.Control)
            SkeletonBone(height = 24.dp)
            SkeletonBone(height = AppSpacing.ControlMinHeight, shape = AppShapes.Control)
        }
        else -> {
            AgePreferenceFields(state.age.copy(saving = state.busy), model::editAge)
            state.error?.let { InlineError(it.stateTitle()) }
            AppButton(stringResource(if (state.error != null) Res.string.state_retry else Res.string.hub_age_save), Modifier.fillMaxWidth(),
                enabled = state.canProceed, loading = state.saving, loadingText = stringResource(Res.string.age_preference_saving), onClick = { model.proceed() })
        }
    }
}

/** 서버에 이미 같은 값이 저장돼 있다(가입 단계만 다시 확인하면 된다) */
private val ConnectedSettingsState.settled get() = editable && kind == SettingsKind.AGE && intended?.valid == true && intended == document?.values
private val ConnectedSettingsState.canProceed get() = canSave || settled
private fun MatchingSettingsViewModel.proceed() = if (state.value.canSave) save() else refreshStage()

/**
 * 가입 준비 화면의 "키링 동기화" 줄: 성향테스트를 마치면 바로 열어 둔다.
 * 서버는 희망 나이가 저장돼야 동기화를 받으므로, 누르면 위에 보이는 희망 나이(기본 "상관없어요")를 저장하고 넘어간다.
 */
@Composable
fun ColumnScope.HubSyncStart(session: Session.SignedIn, model: MatchingSettingsViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    AppButton(stringResource(Res.string.onboarding_hub_sync_start), Modifier.fillMaxWidth(),
        enabled = state.owner == session && state.canProceed, loading = state.saving, onClick = { model.proceed() })
}
