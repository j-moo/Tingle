package com.ssafy.e106.features.account.ui

/** Platform observations, not server consent or push/BLE connection state. Unknown is never denied. */
enum class DevicePermissionStatus { Unknown, Granted, Requestable, SettingsRequired, NotRequired, Unsupported }
enum class DeviceBluetoothStatus { Unknown, On, Off, Unsupported }
enum class DevicePermissionsLoad { Ready, Checking, Failed }
enum class DevicePermissionsAction { Back, Refresh, RequestNotifications, RequestNearby, NotificationSettings, NearbySettings, BluetoothSettings }

data class DevicePermissionsState(
    val load: DevicePermissionsLoad = DevicePermissionsLoad.Ready,
    val notifications: DevicePermissionStatus = DevicePermissionStatus.Unknown,
    val nearby: DevicePermissionStatus = DevicePermissionStatus.Unknown,
    val bluetooth: DeviceBluetoothStatus = DeviceBluetoothStatus.Unknown,
    val actionFailed: Boolean = false,
    // 실제 연결된 관찰 기능만 노출한다. 미제공 BLE 상태를 거절/미지원으로 추정하지 않는다.
    val keyringStatusAvailable: Boolean = true,
    val notificationSettingsFallback: Boolean = false,
) {
    // A refresh failure must not leave stale granted labels or actionable permission requests on screen.
    val visibleNotifications get() = if (load == DevicePermissionsLoad.Ready) notifications else DevicePermissionStatus.Unknown
    val visibleNearby get() = if (load == DevicePermissionsLoad.Ready) nearby else DevicePermissionStatus.Unknown
    val visibleBluetooth get() = if (load == DevicePermissionsLoad.Ready) bluetooth else DeviceBluetoothStatus.Unknown
}
