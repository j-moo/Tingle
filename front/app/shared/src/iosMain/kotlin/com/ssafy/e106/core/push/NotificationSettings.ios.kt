package com.ssafy.e106.core.push

import androidx.compose.runtime.Composable
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/** 이 앱의 iOS 설정 화면(알림 항목 포함)을 연다. 알림 화면으로 바로 가는 주소(UIApplicationOpenNotificationSettingsURLString)는 iOS 16+ 라 쓰지 않는다 */
@Composable
actual fun rememberOpenNotificationSettings(): () -> Unit = {
    NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
        UIApplication.sharedApplication.openURL(it, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }
}
