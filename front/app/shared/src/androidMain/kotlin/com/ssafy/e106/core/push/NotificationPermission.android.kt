package com.ssafy.e106.core.push

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

@Composable
actual fun rememberNotificationPermission(): NotificationPermission {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    LifecycleResumeEffect(Unit) {
        granted = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    // 12 이하는 설치 때 허용돼 있어 요청할 것이 없다(꺼져 있으면 설정 화면으로 보내는 건 화면 쪽)
    return NotificationPermission(granted) { if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}
