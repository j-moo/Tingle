package com.ssafy.e106.features.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ble.BleAvailability
import com.ssafy.e106.core.ble.BlePermission
import com.ssafy.e106.core.ble.rememberBlePermission
import com.ssafy.e106.core.push.NotificationPermission
import com.ssafy.e106.core.push.rememberNotificationPermission
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.screenGutter
import com.ssafy.e106.features.auth.ui.SignupSheet
import com.ssafy.e106.features.profile.ui.NoBackHandling
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 로그인 후(약관 동의 다음) 모든 화면 위에 접근 권한 안내 시트를 얹는다. 키링 연결 권한이 없으면 앱을 쓸 수 없다.
 * 항목은 안내만 하고, 아래 "확인" 하나가 다음에 필요한 일을 한다(권한 요청 → 거절 뒤엔 앱 설정 → 알림 요청 후 통과).
 * 알림은 선택이라 결과와 상관없이 넘어간다. 블루투스 · 위치 서비스 켜짐은 권한이 아니라 여기서 묻지 않고 키링 연결 화면에서 안내한다.
 * ✕ · 뒤로 가기 = 로그아웃. 권한이 이미 있는 기기에서는 시트를 건너뛰고, 통과한 뒤라도 권한이 없어지면 다시 건다.
 */
@Composable
internal fun KeyringPermissionGate(userId: Long, onLogout: () -> Unit, content: @Composable () -> Unit) {
    val ble = rememberBlePermission()
    val notification = rememberNotificationPermission()
    // 알림은 선택이라 판단에 넣지 않는다. 넣으면 알림을 거절한 사람에게 앱을 켤 때마다 시트가 다시 뜬다
    var done by rememberSaveable(userId) { mutableStateOf(ble.availability.permissionGranted) }
    // 통과했어도 지금 권한이 없으면 다시 건다(권한을 회수하면 OS가 앱을 종료하는데, 최근 앱에서 다시 열면 저장된 done 이 복원된다)
    val passed = done && ble.availability.permissionGranted
    // content 는 통과 전후 같은 자리에 둔다. 자리가 바뀌면 그 아래 화면 상태(푸시로 연 채팅 등)가 처음부터 다시 만들어진다.
    // 시트가 떠 있는 동안 뒤 화면은 TalkBack이 읽지 않고 뒤로 가기도 가져가지 않는다
    Box(Modifier.fillMaxSize()) {
        Box(if (passed) Modifier else Modifier.clearAndSetSemantics {}) { NoBackHandling(enabled = !passed) { content() } }
        if (!passed) PermissionSheet(ble, notification, onLogout) { done = true }
    }
}

@Composable
private fun PermissionSheet(ble: BlePermission, notification: NotificationPermission, onLogout: () -> Unit, onPassed: () -> Unit) {
    SignupSheet(onClose = onLogout, background = {}) {
        // 설정 화면이 아예 없는 기기에서도 앱이 죽지 않게 한다(버튼이 반응하지 않을 뿐 ✕로 나갈 수 있다)
        PermissionSheetContent(ble.availability, notification.granted, ble.denied, onConfirm = { runCatching {
            when (ble.availability) {
                // 거절된 뒤(다시 묻지 않음 포함)엔 시스템 창이 안 뜰 수 있어 앱 설정으로
                BleAvailability.PermissionRequired -> if (ble.denied) ble.openAppSettings() else ble.request()
                BleAvailability.Unsupported -> Unit
                else -> { if (!notification.granted) notification.request(); onPassed() }
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
                    stringResource(Res.string.permissions_nearby_body), done = ble.permissionGranted)
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
            ble == BleAvailability.PermissionRequired -> stringResource(Res.string.permission_required_hint)
            else -> null
        }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = AppSpacing.Medium)) }
        AppButton(stringResource(when {
            ble == BleAvailability.PermissionRequired && denied -> Res.string.permission_open_settings
            else -> Res.string.permission_confirm
        }), Modifier.fillMaxWidth().padding(top = AppSpacing.Small), enabled = ble != BleAvailability.Unsupported, onClick = onConfirm)
    }
}

/** 블루투스 · 위치 서비스가 꺼져 있어도 권한 자체는 있는 상태 */
private val BleAvailability.permissionGranted get() = this != BleAvailability.PermissionRequired && this != BleAvailability.Unsupported

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
