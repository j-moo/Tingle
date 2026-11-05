package com.ssafy.e106.core.push

import androidx.compose.runtime.Composable

/** 알림 권한 상태와 요청. 언제 묻고 어떻게 안내할지는 화면이 정한다. [granted] 는 설정에서 바꾸고 돌아오면 다시 읽는다 */
class NotificationPermission(val granted: Boolean, val request: () -> Unit)

@Composable
expect fun rememberNotificationPermission(): NotificationPermission
