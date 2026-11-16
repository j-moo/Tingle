package com.ssafy.e106.features.settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.screenGutter
import com.ssafy.e106.core.ui.theme.screenPaddingValues
import com.ssafy.e106.features.preferences.ui.AgePreferenceScreen
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.features.settings.data.SettingsKind
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** 메뉴를 여는 것만으로 PUT 하지 않는다. 화면 복귀 시 GET으로 버전과 접근 권한을 다시 확인한다. */
@Composable fun ConnectedSettingsRoute(session: Session.SignedIn, onBack: () -> Unit,
    model: MatchingSettingsViewModel = koinViewModel()) {
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
        SettingsFrame(stringResource(Res.string.settings_api_entry), onBack, bottom = {
            // 가입 단계 확인 실패는 버튼 위 한 줄로 알린다
            state.error?.let { InlineError(it.stateTitle()) }
            AppButton(stringResource(Res.string.settings_api_refresh_stage), Modifier.fillMaxWidth(),
                variant = AppButtonVariant.Secondary, enabled = !state.busy, onClick = model::refreshStage)
        }) {
            Text(stringResource(Res.string.settings_api_gate_help), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
            Column {
                SettingsKind.entries.forEach { kind -> ServiceRow(stringResource(if (kind == SettingsKind.AGE)
                    Res.string.age_preference_title else Res.string.settings_matching_title), onClick = {
                    selectedKind = kind.name; selected = true; model.open(kind)
                }) }
            }
        }
        return
    }
    state.conflict?.let { conflict ->
        SettingsConflictScreen(conflict) { action ->
            if (action == ConflictAction.Back) back() else model.conflictAction(action)
        }
        return
    }
    val title = stringResource(if (state.kind == SettingsKind.AGE) Res.string.age_preference_title else Res.string.settings_matching_title)
    val save = stringResource(Res.string.settings_api_save)
    if (state.document == null) {
        // 처음 불러오기 실패 · 가입 단계 차단: 화면 전체를 상태 화면으로(짧은 제목 한 줄 + 작은 "다시 시도")
        if (state.error != null) StateScreen(state.error.stateKind(), state.error.stateTitle(), barTitle = title,
            onBack = ::back, onRefresh = model::load)
        // 불러오는 중: 실제 화면과 같은 틀에 값 자리만 회색으로 둔다
        else if (state.kind == SettingsKind.AGE) AgePreferenceScreen(state.age, {}, {}, onBack = ::back,
            submitLabel = save, loading = true, onRefresh = model::load)
        else SettingsFrame(title, ::back, onRefresh = model::load,
            bottom = { AppButton(save, Modifier.fillMaxWidth(), enabled = false, onClick = {}) }) { MatchingSettingsSkeleton() }
        return
    }
    // 보던 화면에서 새로고침만 실패하면 내용은 그대로 두고 아래 띠로 알린다.
    // 저장 실패는 결과 확인이 먼저라 ViewModel 이 비교 화면(conflict)으로 보낸다(여기서 다시 보내지 않는다)
    val notice: @Composable ColumnScope.() -> Unit = {
        if (state.saved) Text(stringResource(Res.string.settings_api_saved),
            Modifier.padding(horizontal = screenGutter(), vertical = AppSpacing.Small),
            style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        state.error?.let { NoticeBar(it.stateTitle(), actionEnabled = !state.busy, onAction = model::load) }
    }
    val refreshing = state.busy && !state.saving
    if (state.kind == SettingsKind.AGE) AgePreferenceScreen(
        state.age.copy(saving = state.busy), model::editAge, model::save, onBack = ::back, submitEnabled = state.canSave,
        submitLabel = save, refreshing = refreshing, onRefresh = model::load, notice = notice)
    else SettingsFrame(title, ::back, refreshing, model::load, notice, bottom = {
        AppButton(save, Modifier.fillMaxWidth(), enabled = state.canSave, loading = state.saving, onClick = model::save)
    }) {
        SettingsToggle(Res.string.settings_matching, Res.string.settings_matching_help,
            state.matching?.enabled, state.editable) { model.editMatching(enabled = it) }
        SettingsToggle(Res.string.settings_photo, Res.string.settings_photo_help,
            state.matching?.photoRequired, state.editable) { model.editMatching(photoRequired = it) }
        Text(stringResource(Res.string.settings_existing_help), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        Text(stringResource(Res.string.settings_api_sync_unconfirmed), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
    }
}

/**
 * 설정 화면 틀: 위쪽 줄(제목 · 이전) + 스크롤 내용 + 아래 띠([notice]) + 아래 고정 버튼([bottom]).
 * [onRefresh] 가 있으면 당겨서 새로고침이 된다. 시스템 버튼 영역 위에서 끝난다(safeDrawingPadding).
 */
@Composable
private fun SettingsFrame(title: String, onBack: () -> Unit, refreshing: Boolean = false, onRefresh: (() -> Unit)? = null,
    notice: @Composable ColumnScope.() -> Unit = {}, bottom: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            AppTopBar(title, onBack = onBack)
            // 빈 곳을 당겨도 새로고침이 되게 화면 높이를 다 쓰고 스크롤할 수 있게 둔다
            val body: @Composable BoxScope.() -> Unit = {
                Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Section), content = content)
            }
            if (onRefresh != null) PullDownRefreshBox(refreshing, onRefresh, Modifier.weight(1f).fillMaxWidth(), content = body)
            else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter, content = body)
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), content = notice)
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = screenGutter())
                .padding(top = AppSpacing.Small, bottom = AppSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium), content = bottom)
        }
    }
}

/** 매칭 설정 뼈대: 항목 이름 · 설명은 그대로 두고 스위치 자리만 회색으로 둔다 */
@Composable
private fun MatchingSettingsSkeleton() {
    SkeletonGroup(spacing = AppSpacing.Section) {
        listOf(Res.string.settings_matching to Res.string.settings_matching_help,
            Res.string.settings_photo to Res.string.settings_photo_help).forEach { (name, help) ->
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                Row(Modifier.fillMaxWidth().heightIn(min = AppSpacing.ControlMinHeight).padding(AppSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                    Text(stringResource(name), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary)
                    SkeletonBone(width = 52.dp, height = 32.dp, shape = CircleShape)
                }
                Text(stringResource(help), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
        }
    }
}
