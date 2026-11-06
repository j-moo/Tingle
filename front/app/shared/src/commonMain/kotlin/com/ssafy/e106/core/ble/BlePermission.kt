package com.ssafy.e106.core.ble

import androidx.compose.runtime.Composable

/** 권한/전원/위치 서비스는 통신 준비 조건일 뿐 연결·등록 완료가 아니다. */
enum class BleAvailability { Unsupported, PermissionRequired, BluetoothOff, LocationOff, Ready }
/** [denied]: 이번 실행에서 요청했는데 거절됨(다시 묻지 않음 포함 — 이때는 시스템 창 없이 바로 거절된다). 다음은 앱 설정으로 보낸다 */
class BlePermission(val availability: BleAvailability, val request: () -> Unit,
    val openAppSettings: () -> Unit, val openBluetoothSettings: () -> Unit, val openLocationSettings: () -> Unit,
    val denied: Boolean = false)

@Composable expect fun rememberBlePermission(): BlePermission
