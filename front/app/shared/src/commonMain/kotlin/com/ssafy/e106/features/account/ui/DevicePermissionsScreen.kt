package com.ssafy.e106.features.account.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun DevicePermissionsScreen(state: DevicePermissionsState, onAction: (DevicePermissionsAction) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.account_permissions), onBack = { onAction(DevicePermissionsAction.Back) })
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    Text(stringResource(Res.string.permissions_heading), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(Res.string.permissions_intro))
                    if (state.load == DevicePermissionsLoad.Failed) StatusCard(stringResource(Res.string.permissions_failed), stringResource(Res.string.permissions_failed_body))
                    if (state.actionFailed) StatusCard(stringResource(Res.string.permissions_action_failed), stringResource(Res.string.permissions_action_failed_body))
                    PermissionItem(stringResource(Res.string.permissions_notifications), stringResource(Res.string.permissions_notifications_body),
                        state.visibleNotifications, { onAction(DevicePermissionsAction.RequestNotifications) }, { onAction(DevicePermissionsAction.NotificationSettings) })
                    // 요청 팝업이 생략될 수 있어도 사용자가 직접 설정으로 이동할 수 있게 한다.
                    if (state.notificationSettingsFallback && state.visibleNotifications == DevicePermissionStatus.Requestable) {
                        Text(stringResource(Res.string.permissions_notification_fallback), style = MaterialTheme.typography.bodySmall)
                        AppButton(stringResource(Res.string.permissions_settings), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                            onClick = { onAction(DevicePermissionsAction.NotificationSettings) })
                    }
                    if (state.keyringStatusAvailable) {
                        PermissionItem(stringResource(Res.string.permissions_nearby), stringResource(Res.string.permissions_nearby_body),
                            state.visibleNearby, { onAction(DevicePermissionsAction.RequestNearby) }, { onAction(DevicePermissionsAction.NearbySettings) })
                        PermissionPanel(stringResource(Res.string.permissions_bluetooth), stringResource(Res.string.permissions_bluetooth_body)) {
                            val bluetooth = state.visibleBluetooth
                            PermissionStatusText(stringResource(when (bluetooth) {
                                DeviceBluetoothStatus.Unknown -> Res.string.permissions_unknown
                                DeviceBluetoothStatus.On -> Res.string.permissions_on
                                DeviceBluetoothStatus.Off -> Res.string.permissions_off
                                DeviceBluetoothStatus.Unsupported -> Res.string.permissions_unsupported
                            }), bluetooth == DeviceBluetoothStatus.On)
                            if (bluetooth == DeviceBluetoothStatus.Off || bluetooth == DeviceBluetoothStatus.On) {
                                AppButton(stringResource(Res.string.permissions_bluetooth_settings), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                                    onClick = { onAction(DevicePermissionsAction.BluetoothSettings) })
                            }
                        }
                    } else {
                        StatusCard(stringResource(Res.string.permissions_nearby), stringResource(Res.string.permissions_keyring_pending))
                    }
                    Text(stringResource(Res.string.permissions_separate), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    AppButton(stringResource(Res.string.permissions_refresh), Modifier.fillMaxWidth(), loading = state.load == DevicePermissionsLoad.Checking,
                        loadingText = stringResource(Res.string.permissions_checking), onClick = { onAction(DevicePermissionsAction.Refresh) })
                    Text(stringResource(Res.string.permissions_return), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                }
            }
        }
    }
}

@Composable private fun PermissionItem(title: String, body: String, status: DevicePermissionStatus, request: () -> Unit, settings: () -> Unit) {
    PermissionPanel(title, body) {
        PermissionStatusText(stringResource(when (status) {
            DevicePermissionStatus.Unknown -> Res.string.permissions_unknown
            DevicePermissionStatus.Granted -> Res.string.permissions_granted
            DevicePermissionStatus.Requestable -> Res.string.permissions_requestable
            DevicePermissionStatus.SettingsRequired -> Res.string.permissions_settings_required
            DevicePermissionStatus.NotRequired -> Res.string.permissions_not_required
            DevicePermissionStatus.Unsupported -> Res.string.permissions_unsupported
        }), status == DevicePermissionStatus.Granted)
        when (status) {
            DevicePermissionStatus.Requestable -> AppButton(stringResource(Res.string.permissions_request), Modifier.fillMaxWidth(), onClick = request)
            DevicePermissionStatus.Granted, DevicePermissionStatus.SettingsRequired -> AppButton(stringResource(Res.string.permissions_settings), Modifier.fillMaxWidth(),
                variant = AppButtonVariant.Secondary, onClick = settings)
            else -> Unit
        }
    }
}

@Composable private fun PermissionPanel(title: String, body: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = AppColors.SurfaceMuted, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.Large), verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

@Composable private fun PermissionStatusText(text: String, positive: Boolean) {
    Surface(color = if (positive) AppColors.SelectionContainer else AppColors.Background, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
            style = MaterialTheme.typography.labelLarge, color = if (positive) AppColors.OnSelection else AppColors.TextSecondary)
    }
}
