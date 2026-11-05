package com.ssafy.e106.features.settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 실물 BLE나 소유권 변경을 실행하지 않는 순수 키링 관리 화면. */
@Composable
fun KeyringSettingsScreen(state: KeyringSettingsState, onVibrationChange: (Boolean) -> Unit,
    onLedChange: (Boolean) -> Unit, onAction: (KeyringSettingsAction) -> Unit) {
    SettingsPage(Res.string.settings_keyring_title,
        state.load == SettingsLoad.Loading || state.save != SettingsSave.Idle,
        { onAction(KeyringSettingsAction.Back) }, { onAction(KeyringSettingsAction.Refresh) }) {
        SettingsLoadNotice(state.load) { onAction(KeyringSettingsAction.Refresh) }
        if (state.load == SettingsLoad.Ready) {
            if (state.hasKeyring != true) {
                SettingsFeedback(SettingsSave.Idle, state.displaySync, {}, {})
                if (state.hasKeyring == false) {
                    AppButton(stringResource(Res.string.settings_register), Modifier.fillMaxWidth(),
                        onClick = { onAction(KeyringSettingsAction.Register) })
                    Text(stringResource(Res.string.settings_no_keyring_existing), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                SettingsFeedback(state.save, state.displaySync,
                    { onAction(KeyringSettingsAction.RetrySave) }, { onAction(KeyringSettingsAction.CheckSave) })
                val unknown = stringResource(Res.string.settings_unknown)
                val battery = state.batteryPercent?.takeIf { it in 0..100 }
                    ?.let { stringResource(Res.string.settings_battery_value, it) } ?: unknown
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                    Text(stringResource(Res.string.settings_device), style = MaterialTheme.typography.titleLarge)
                    if (state.connection != KeyringConnection.Connected) {
                        Text(stringResource(Res.string.settings_last_known), style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextSecondary)
                    }
                    ServiceRow(stringResource(Res.string.settings_connection), detail = stringResource(when (state.connection) {
                        KeyringConnection.Connected -> Res.string.settings_connected
                        KeyringConnection.Disconnected -> Res.string.settings_disconnected
                        KeyringConnection.Unknown -> Res.string.settings_unknown
                    }))
                    ServiceRow(stringResource(Res.string.settings_battery), detail = battery)
                    ServiceRow(stringResource(Res.string.settings_firmware), detail = state.firmware?.takeIf { it.isNotBlank() } ?: unknown)
                    ServiceRow(stringResource(Res.string.settings_last_sync), detail = state.lastSyncLabel?.takeIf { it.isNotBlank() } ?: unknown)
                }
                SettingsToggle(Res.string.settings_vibration, Res.string.settings_vibration_help,
                    state.vibration, state.editable, onVibrationChange)
                SettingsToggle(Res.string.settings_led, Res.string.settings_led_help, state.led, state.editable, onLedChange)
                Text(stringResource(Res.string.settings_output_help), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                AppButton(stringResource(Res.string.settings_sync_now), Modifier.fillMaxWidth(),
                    enabled = state.canSync, loading = state.displaySync == SettingsSync.Syncing && state.save == SettingsSave.Idle,
                    loadingText = stringResource(Res.string.settings_syncing),
                    onClick = { if (state.canSync) onAction(KeyringSettingsAction.Sync) })
                Text(stringResource(Res.string.settings_auto_sync_help), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                ServiceRow(stringResource(Res.string.settings_pending_records), detail = countLabel(state.pendingUploadCount))
                ServiceRow(stringResource(Res.string.settings_waiting_peer), detail = countLabel(state.waitingPeerCount))
                ServiceRow(stringResource(Res.string.settings_reset), detail = stringResource(Res.string.settings_reset_help),
                    onClick = if (state.save == SettingsSave.Idle && state.displaySync != SettingsSync.Syncing)
                        ({ onAction(KeyringSettingsAction.Reset) }) else null)
            }
            ServiceRow(stringResource(Res.string.settings_lost), detail = stringResource(Res.string.settings_lost_help),
                onClick = { onAction(KeyringSettingsAction.LostSupport) })
        }
    }
}

@Composable
private fun countLabel(value: Int?): String = value?.takeIf { it >= 0 }
    ?.let { stringResource(Res.string.settings_count, it) } ?: stringResource(Res.string.settings_unknown)
