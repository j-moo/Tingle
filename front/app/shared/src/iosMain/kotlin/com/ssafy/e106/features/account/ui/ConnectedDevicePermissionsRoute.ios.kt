package com.ssafy.e106.features.account.ui

import androidx.compose.runtime.Composable

/** iOS 권한 기반197은 아직 미제공이다. TODO 기반을 호출하거나 예시 권한을 실제 상태로 표시하지 않는다. */
@Composable
actual fun ConnectedDevicePermissionsRoute(onBack: () -> Unit) {
    DevicePermissionsScreen(DevicePermissionsState(
        notifications = DevicePermissionStatus.Unsupported,
        keyringStatusAvailable = false,
    ), onAction = { action -> if (action == DevicePermissionsAction.Back) onBack() })
}
