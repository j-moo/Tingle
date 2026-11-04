package com.ssafy.e106.core.ble

import androidx.compose.runtime.Composable

/** 현재 Android 구현 범위. iOS에서 권한 허용/연결 성공을 흉내 내지 않는다. */
@Composable actual fun rememberBlePermission() = BlePermission(BleAvailability.Unsupported, {}, {}, {}, {})
