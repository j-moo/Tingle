package com.ssafy.e106.features.settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingsPage(title: StringResource, busy: Boolean, onBack: () -> Unit, onRefresh: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(title), onBack = onBack,
                actionText = if (busy) null else stringResource(Res.string.settings_refresh), onAction = onRefresh)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(screenPaddingValues()), verticalArrangement = Arrangement.spacedBy(AppSpacing.Section),
                    content = content)
            }
        }
    }
}

@Composable
internal fun SettingsLoadNotice(load: SettingsLoad, retry: () -> Unit) {
    if (load == SettingsLoad.Loading) {
        StatusCard(stringResource(Res.string.settings_loading), stringResource(Res.string.settings_loading_help))
        CircularProgressIndicator(color = AppColors.SelectionBorder)
    } else if (load == SettingsLoad.Failed) {
        StatusCard(stringResource(Res.string.settings_load_failed), stringResource(Res.string.settings_load_failed_help))
        AppButton(stringResource(Res.string.settings_retry_load), Modifier.fillMaxWidth(), onClick = retry)
    }
}

@Composable
internal fun SettingsToggle(title: StringResource, help: StringResource, value: Boolean?, enabled: Boolean,
    onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        if (value == null) ServiceRow(stringResource(title), detail = stringResource(Res.string.settings_unknown))
        else AppSwitch(value, onChange, stringResource(title), enabled = enabled)
        Text(stringResource(help), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
    }
}

@Composable
internal fun SettingsFeedback(save: SettingsSave, sync: SettingsSync, retry: () -> Unit, check: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        when (save) {
            SettingsSave.Idle -> SyncNotice(sync)
            SettingsSave.Changed -> StatusCard(stringResource(Res.string.settings_changed), stringResource(Res.string.settings_changed_help))
            SettingsSave.Saving -> {
                StatusCard(stringResource(Res.string.settings_saving), stringResource(Res.string.settings_saving_help))
                CircularProgressIndicator(color = AppColors.SelectionBorder)
            }
            SettingsSave.Failed -> {
                StatusCard(stringResource(Res.string.settings_save_failed), stringResource(Res.string.settings_save_failed_help))
                AppButton(stringResource(Res.string.settings_retry_save), Modifier.fillMaxWidth(), onClick = retry)
            }
            SettingsSave.Unknown -> {
                StatusCard(stringResource(Res.string.settings_save_unknown), stringResource(Res.string.settings_save_unknown_help))
                AppButton(stringResource(Res.string.settings_check_save), Modifier.fillMaxWidth(), onClick = check)
            }
        }
    }
}

@Composable
private fun SyncNotice(sync: SettingsSync) {
    val copy = when (sync) {
        SettingsSync.Unknown -> Res.string.settings_sync_unknown to Res.string.settings_sync_unknown_help
        SettingsSync.NoKeyring -> Res.string.settings_no_keyring to Res.string.settings_no_keyring_help
        SettingsSync.Disconnected -> Res.string.settings_disconnected to Res.string.settings_disconnected_help
        SettingsSync.Pending -> Res.string.settings_pending to Res.string.settings_pending_help
        SettingsSync.Syncing -> Res.string.settings_syncing to Res.string.settings_syncing_help
        SettingsSync.Applied -> Res.string.settings_applied to Res.string.settings_applied_help
        SettingsSync.Failed -> Res.string.settings_sync_failed to Res.string.settings_sync_failed_help
        SettingsSync.RecheckRequired -> Res.string.settings_recheck to Res.string.settings_recheck_help
    }
    StatusCard(stringResource(copy.first), stringResource(copy.second))
}
