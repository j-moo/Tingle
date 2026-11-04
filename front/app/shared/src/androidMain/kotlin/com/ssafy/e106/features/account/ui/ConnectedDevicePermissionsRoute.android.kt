package com.ssafy.e106.features.account.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.ssafy.e106.core.push.rememberNotificationPermission
import com.ssafy.e106.core.ble.*
import com.ssafy.e106.features.profile.ui.ProfileBackHandler

/** 순철의 알림 권한 기반을 소비하며 권한 요청·관찰 로직을 다시 구현하지 않는다. */
@Composable
actual fun ConnectedDevicePermissionsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var actionFailed by remember { mutableStateOf(false) }
    ProfileBackHandler(true, onBack)
    // 수동 새로고침은 기반을 다시 관찰한다. 설정 복귀 갱신은 기반의 LifecycleResumeEffect가 담당한다.
    key(refresh) {
        val permission = rememberNotificationPermission()
        val ble = rememberBlePermission()
        DevicePermissionsScreen(DevicePermissionsState(
            notifications = when {
                permission.granted -> DevicePermissionStatus.Granted
                Build.VERSION.SDK_INT >= 33 -> DevicePermissionStatus.Requestable
                else -> DevicePermissionStatus.SettingsRequired
            },
            actionFailed = actionFailed,
            // 권한과 블루투스 전원을 구분한다. 권한이 없어 전원을 읽지 못하면 Unknown이다.
            nearby = when (ble.availability) {
                BleAvailability.Unsupported -> DevicePermissionStatus.Unsupported
                BleAvailability.PermissionRequired -> DevicePermissionStatus.Requestable
                else -> DevicePermissionStatus.Granted
            },
            bluetooth = when (ble.availability) {
                BleAvailability.Unsupported -> DeviceBluetoothStatus.Unsupported
                BleAvailability.PermissionRequired -> DeviceBluetoothStatus.Unknown
                BleAvailability.BluetoothOff -> DeviceBluetoothStatus.Off
                else -> DeviceBluetoothStatus.On
            },
            keyringStatusAvailable = true,
            notificationSettingsFallback = true,
        ), onAction = { action ->
            when (action) {
                DevicePermissionsAction.Back -> onBack()
                DevicePermissionsAction.Refresh -> { actionFailed = false; refresh++ }
                DevicePermissionsAction.RequestNotifications -> {
                    // 자동으로 권한을 요청하지 않는다. 사용자가 버튼을 누른 경우에만 기반을 호출한다.
                    actionFailed = runCatching { permission.request() }.isFailure
                }
                DevicePermissionsAction.NotificationSettings -> {
                    actionFailed = !openNotificationSettings(context)
                }
                DevicePermissionsAction.RequestNearby -> { actionFailed = runCatching { ble.request() }.isFailure }
                DevicePermissionsAction.NearbySettings -> { actionFailed = runCatching { ble.openAppSettings() }.isFailure }
                DevicePermissionsAction.BluetoothSettings -> { actionFailed = runCatching { ble.openBluetoothSettings() }.isFailure }
            }
        })
    }
}

private fun openNotificationSettings(context: Context): Boolean {
    val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    val notifications = if (Build.VERSION.SDK_INT >= 26) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else appSettings
    // 제조사 설정 앱이 알림 전용 화면을 지원하지 않으면 같은 앱의 상세 설정으로만 대체한다.
    fun launch(intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    return launch(notifications) || (notifications !== appSettings && launch(appSettings))
}
