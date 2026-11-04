package com.ssafy.e106.features.account.ui

import androidx.compose.runtime.Composable

/** OS 권한은 서버 동의와 별개다. 플랫폼 기반으로 관찰한 값만 실제 화면에 전달한다. */
@Composable
expect fun ConnectedDevicePermissionsRoute(onBack: () -> Unit)
