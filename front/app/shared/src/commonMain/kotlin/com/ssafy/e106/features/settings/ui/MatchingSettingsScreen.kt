package com.ssafy.e106.features.settings.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.ssafy.e106.core.ui.components.ServiceRow
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 화면 상태와 선택만 전달한다. 나이 입력은 기존 희망 나이 화면으로 연결할 진입점이다. */
@Composable
fun MatchingSettingsScreen(state: MatchingSettingsState, onMatchingChange: (Boolean) -> Unit,
    onPhotoChange: (Boolean) -> Unit, onAction: (MatchingSettingsAction) -> Unit,
    onConflictAction: (ConflictAction<ConflictValues.Matching>) -> Unit = {}) {
    state.conflict?.let { SettingsConflictScreen(it, onConflictAction); return }
    SettingsPage(Res.string.settings_matching_title,
        state.load == SettingsLoad.Loading || state.save != SettingsSave.Idle,
        { onAction(MatchingSettingsAction.Back) }, { onAction(MatchingSettingsAction.Refresh) }) {
        SettingsLoadNotice(state.load) { onAction(MatchingSettingsAction.Refresh) }
        if (state.load == SettingsLoad.Ready) {
            SettingsFeedback(state.save, state.sync,
                { onAction(MatchingSettingsAction.RetrySave) }, { onAction(MatchingSettingsAction.CheckSave) })
            SettingsToggle(Res.string.settings_matching, Res.string.settings_matching_help,
                state.matching, state.editable, onMatchingChange)
            ServiceRow(stringResource(Res.string.settings_age),
                detail = state.ageLabel?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.settings_unknown),
                onClick = if (state.editable) ({ onAction(MatchingSettingsAction.Age) }) else null)
            SettingsToggle(Res.string.settings_photo, Res.string.settings_photo_help,
                state.photoRequired, state.editable, onPhotoChange)
            Text(stringResource(Res.string.settings_existing_help), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            ServiceRow(stringResource(Res.string.settings_keyring_title), detail = stringResource(Res.string.settings_keyring_link),
                onClick = if (state.save == SettingsSave.Idle) ({ onAction(MatchingSettingsAction.Keyring) }) else null)
        }
    }
}
