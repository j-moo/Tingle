package com.ssafy.e106.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.account.ui.*

private val permissionExample = DevicePermissionsState(notifications = DevicePermissionStatus.Requestable,
    nearby = DevicePermissionStatus.Requestable, bluetooth = DeviceBluetoothStatus.Off)
@Composable private fun PermissionPreview(state: DevicePermissionsState) = AppTheme { DevicePermissionsScreen(state, {}) }

@Preview(name = "01 권한 요청 필요", group = "기기 권한 안내", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PermissionsRequestPreview() = PermissionPreview(permissionExample)
@Preview(name = "02 허용됨", group = "기기 권한 안내", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PermissionsGrantedPreview() = PermissionPreview(DevicePermissionsState(notifications = DevicePermissionStatus.Granted,
    nearby = DevicePermissionStatus.Granted, bluetooth = DeviceBluetoothStatus.On))
@Preview(name = "03 설정에서 변경", group = "기기 권한 안내", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PermissionsSettingsPreview() = PermissionPreview(permissionExample.copy(notifications = DevicePermissionStatus.SettingsRequired, nearby = DevicePermissionStatus.SettingsRequired))
@Preview(name = "04 상태 미확인", group = "기기 권한 안내", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PermissionsUnknownPreview() = PermissionPreview(DevicePermissionsState())
@Preview(name = "05 확인 중", group = "기기 권한 안내", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PermissionsCheckingPreview() = PermissionPreview(permissionExample.copy(load = DevicePermissionsLoad.Checking))
@Preview(name = "06 조회 실패", group = "기기 권한 안내", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PermissionsFailedPreview() = PermissionPreview(permissionExample.copy(load = DevicePermissionsLoad.Failed))
@Preview(name = "07 불필요·미지원", group = "기기 권한 안내", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PermissionsUnsupportedPreview() = PermissionPreview(DevicePermissionsState(notifications = DevicePermissionStatus.NotRequired,
    nearby = DevicePermissionStatus.Unsupported, bluetooth = DeviceBluetoothStatus.Unsupported))
@Preview(name = "08 열기 실패", group = "기기 권한 안내", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PermissionsOpenFailedPreview() = PermissionPreview(permissionExample.copy(actionFailed = true))
@Preview(name = "09 작은 화면·큰 글자", group = "기기 권한 안내", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ko")
@Composable fun PermissionsCompactPreview() = PermissionPreview(permissionExample)
