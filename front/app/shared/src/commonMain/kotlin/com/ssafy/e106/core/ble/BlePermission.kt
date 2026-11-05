package com.ssafy.e106.core.ble

import androidx.compose.runtime.Composable

/** 권한/전원/위치 서비스는 통신 준비 조건일 뿐 연결·등록 완료가 아니다. */
enum class BleAvailability { Unsupported, PermissionRequired, BluetoothOff, LocationOff, Ready }
class BlePermission(val availability: BleAvailability, val request: () -> Unit,
    val openAppSettings: () -> Unit, val openBluetoothSettings: () -> Unit, val openLocationSettings: () -> Unit)

@Composable expect fun rememberBlePermission(): BlePermission
