package com.ssafy.e106.features.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ble.BleAvailability
import com.ssafy.e106.core.ble.rememberBlePermission
import com.ssafy.e106.core.push.rememberNotificationPermission
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.screenGutter
import com.ssafy.e106.features.auth.ui.SignupSheet
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * 키링 단계에 처음 들어오면 화면 위에 권한 안내 시트를 얹는다. 필수(근처 기기 · 블루투스 · 옛 기기의 위치 서비스)가 모두 준비돼야 "다음",
 * 알림은 선택. ✕ · 뒤로 가기 = 로그아웃(필수 권한 없이는 가입을 이어갈 수 없다). 이미 모두 준비된 기기에서는 시트를 건너뛴다.
 */
@Composable
internal fun KeyringPermissionGate(userId: Long, onLogout: () -> Unit, content: @Composable () -> Unit) {
    val ble = rememberBlePermission()
    val notification = rememberNotificationPermission()
    var done by rememberSaveable(userId) { mutableStateOf(ble.availability == BleAvailability.Ready && notification.granted) }
    var bleAsked by rememberSaveable(userId) { mutableStateOf(false) }             // 한 번 거절하면 시스템 창이 다시 안 뜰 수 있어 그 뒤엔 앱 설정으로
    var notificationAsked by rememberSaveable(userId) { mutableStateOf(false) }
    if (done) { content(); return }
    SignupSheet(onClose = onLogout, background = content) {
        PermissionSheetContent(
            ble = ble.availability, notificationGranted = notification.granted,
            bleAsked = bleAsked, notificationAsked = notificationAsked,
            onNearby = { if (bleAsked) ble.openAppSettings() else { bleAsked = true; ble.request() } },
            onBluetooth = ble.openBluetoothSettings, onLocation = ble.openLocationSettings,
            onNotification = { if (notificationAsked) ble.openAppSettings() else { notificationAsked = true; notification.request() } },
            onContinue = { done = true },
        )
    }
}

/** 순수 화면(Preview 용). 상태는 받기만 하고 권한 요청 · 설정 이동은 콜백으로 넘긴다 */
@Composable
fun PermissionSheetContent(
    ble: BleAvailability,
    notificationGranted: Boolean,
    bleAsked: Boolean = false,
    notificationAsked: Boolean = false,
    onNearby: () -> Unit = {},
    onBluetooth: () -> Unit = {},
    onLocation: () -> Unit = {},
    onNotification: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
    val granted = ble != BleAvailability.PermissionRequired && ble != BleAvailability.Unsupported
    Column(Modifier.fillMaxSize().padding(horizontal = screenGutter()).padding(bottom = AppSpacing.Large)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            Text(stringResource(Res.string.permission_sheet_title), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(stringResource(Res.string.permission_sheet_body), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
            Spacer(Modifier.padding(top = AppSpacing.Small))
            if (ble == BleAvailability.Unsupported) {
                Text(stringResource(Res.string.permission_unsupported), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            PermissionItem(stringResource(Res.string.permissions_nearby), stringResource(Res.string.permissions_nearby_body), required = true,
                done = granted, action = if (granted || ble == BleAvailability.Unsupported) null
                    else stringResource(if (bleAsked) Res.string.permission_open_settings else Res.string.permission_allow), onAction = onNearby)
            HorizontalDivider(color = AppColors.BorderSubtle)
            // 권한이 있어야 블루투스 상태를 읽을 수 있다 — 그 전엔 버튼 없이 순서만 안내
            PermissionItem(stringResource(Res.string.permissions_bluetooth), stringResource(Res.string.permissions_bluetooth_body), required = true,
                done = ble == BleAvailability.LocationOff || ble == BleAvailability.Ready,
                action = if (ble == BleAvailability.BluetoothOff) stringResource(Res.string.permission_turn_on) else null, onAction = onBluetooth,
                note = if (!granted && ble != BleAvailability.Unsupported) stringResource(Res.string.permission_after_nearby) else null)
            if (ble == BleAvailability.LocationOff) {
                HorizontalDivider(color = AppColors.BorderSubtle)
                PermissionItem(stringResource(Res.string.permission_location), stringResource(Res.string.permission_location_body), required = true,
                    done = false, action = stringResource(Res.string.permission_turn_on), onAction = onLocation)
            }
            HorizontalDivider(color = AppColors.BorderSubtle)
            PermissionItem(stringResource(Res.string.permissions_notifications), stringResource(Res.string.permissions_notifications_body), required = false,
                done = notificationGranted, action = if (notificationGranted) null
                    else stringResource(if (notificationAsked) Res.string.permission_open_settings else Res.string.permission_allow), onAction = onNotification)
        }
        AppButton(stringResource(Res.string.permission_next), Modifier.fillMaxWidth().padding(top = AppSpacing.Medium),
            enabled = ble == BleAvailability.Ready, onClick = onContinue)
        if (ble != BleAvailability.Ready) Text(stringResource(Res.string.permission_required_hint), style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = AppSpacing.Small))
    }
}

@Composable
private fun PermissionItem(title: String, body: String, required: Boolean, done: Boolean, action: String?, onAction: () -> Unit, note: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = AppSpacing.Small), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(if (required) Res.string.permission_required else Res.string.permission_optional),
                    style = MaterialTheme.typography.labelSmall, color = if (required) AppColors.OnPrimary else AppColors.TextSecondary,
                    modifier = Modifier.background(if (required) AppColors.ActionPrimary else AppColors.SurfaceMuted, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 2.dp))
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary) }
        }
        when {
            done -> Text(stringResource(Res.string.permission_done), style = MaterialTheme.typography.labelLarge, color = AppColors.OnSelection)
            action != null -> TextButton(onClick = onAction) { Text(action, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary) }
        }
    }
}
