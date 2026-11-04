package com.ssafy.e106.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.account.ui.ConnectedDevicePermissionsRoute

/** 개발용 실행 진입점이며 가짜 계정·권한·서버 데이터는 만들지 않는다. */
class NotificationPermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { ConnectedDevicePermissionsRoute(onBack = { finish() }) } }
    }
}
