package com.ssafy.e106.features.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
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
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 키링 단계에 처음 들어오면 화면 위에 접근 권한 안내 시트를 얹는다. 항목은 안내만 하고, 아래 "확인" 하나가 다음에 필요한 일을 한다
 * (권한 요청 → 거절 뒤엔 앱 설정 → 블루투스 · 위치 켜기 → 알림 요청 후 통과). 알림은 선택이라 결과와 상관없이 넘어간다.
 * ✕ · 뒤로 가기 = 로그아웃(필수 권한 없이는 가입을 이어갈 수 없다). 이미 모두 준비된 기기에서는 시트를 건너뛴다.
 */
@Composable
internal fun KeyringPermissionGate(userId: Long, onLogout: () -> Unit, content: @Composable () -> Unit) {
    val ble = rememberBlePermission()
    val notification = rememberNotificationPermission()
    // 알림은 선택이라 판단에 넣지 않는다. 넣으면 알림을 거절한 사람에게 키링 단계에 돌아올 때마다 시트가 다시 뜬다
    var done by rememberSaveable(userId) { mutableStateOf(ble.availability == BleAvailability.Ready) }
    if (done) { content(); return }
    SignupSheet(onClose = onLogout, background = content) {
        // 설정 화면이 아예 없는 기기에서도 앱이 죽지 않게 한다(버튼이 반응하지 않을 뿐 ✕로 나갈 수 있다)
        PermissionSheetContent(ble.availability, notification.granted, ble.denied, onConfirm = { runCatching {
            when (ble.availability) {
                // 거절된 뒤(다시 묻지 않음 포함)엔 시스템 창이 안 뜰 수 있어 앱 설정으로
                BleAvailability.PermissionRequired -> if (ble.denied) ble.openAppSettings() else ble.request()
                BleAvailability.BluetoothOff -> ble.openBluetoothSettings()
                BleAvailability.LocationOff -> ble.openLocationSettings()
                BleAvailability.Ready -> { if (!notification.granted) notification.request(); done = true }
                BleAvailability.Unsupported -> Unit
            }
        } })
    }
}

/** 순수 화면(Preview 용). 상태는 받기만 하고 "확인"이 할 일은 [onConfirm] 으로 넘긴다 */
@Composable
fun PermissionSheetContent(
    ble: BleAvailability,
    notificationGranted: Boolean,
    denied: Boolean = false,
    onConfirm: () -> Unit = {},
) {
    val granted = ble != BleAvailability.PermissionRequired && ble != BleAvailability.Unsupported
    // 시트가 위쪽 inset만 소비하므로 아래 내비게이션 바 여백은 여기서 넣는다(위는 0이 되어 중복되지 않는다)
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = screenGutter()).padding(bottom = AppSpacing.Large)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
            Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                Text(stringResource(Res.string.permission_sheet_title_accent), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = AppColors.OnSelection)
                Text(stringResource(Res.string.permission_sheet_title), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            }
            PermissionSection(stringResource(Res.string.permission_section_required)) {
                PermissionItem(Res.drawable.ic_permission_nearby, stringResource(Res.string.permissions_nearby),
                    stringResource(Res.string.permissions_nearby_body), done = granted)
                PermissionItem(Res.drawable.ic_permission_bluetooth, stringResource(Res.string.permissions_bluetooth),
                    stringResource(Res.string.permissions_bluetooth_body), done = ble == BleAvailability.LocationOff || ble == BleAvailability.Ready)
                if (ble == BleAvailability.LocationOff) PermissionItem(Res.drawable.ic_permission_location,
                    stringResource(Res.string.permission_location), stringResource(Res.string.permission_location_body), done = false)
            }
            HorizontalDivider(color = AppColors.BorderSubtle)
            PermissionSection(stringResource(Res.string.permission_section_optional)) {
                PermissionItem(Res.drawable.ic_permission_notifications, stringResource(Res.string.permissions_notifications),
                    stringResource(Res.string.permissions_notifications_body), done = notificationGranted)
            }
        }
        when {
            ble == BleAvailability.Unsupported -> stringResource(Res.string.permission_unsupported)
            ble == BleAvailability.PermissionRequired && denied -> stringResource(Res.string.permission_denied_hint)
            ble != BleAvailability.Ready -> stringResource(Res.string.permission_required_hint)
            else -> null
        }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = AppSpacing.Medium)) }
        AppButton(stringResource(when {
            ble == BleAvailability.PermissionRequired && denied -> Res.string.permission_open_settings
            ble == BleAvailability.BluetoothOff -> Res.string.permission_bluetooth_on
            ble == BleAvailability.LocationOff -> Res.string.permission_location_on
            else -> Res.string.permission_confirm
        }), Modifier.fillMaxWidth().padding(top = AppSpacing.Small), enabled = ble != BleAvailability.Unsupported, onClick = onConfirm)
    }
}

@Composable
private fun PermissionSection(title: String, items: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary,
            modifier = Modifier.semantics { heading() })
        items()
    }
}

@Composable
private fun PermissionItem(icon: DrawableResource, title: String, body: String, done: Boolean) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        Icon(painterResource(icon), contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                if (done) Text(stringResource(Res.string.permission_done), style = MaterialTheme.typography.labelSmall, color = AppColors.OnSelection)
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
    }
}
