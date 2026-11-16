package com.ssafy.e106.features.profile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner

@Composable internal actual fun ProfileBackHandler(enabled: Boolean, onBack: () -> Unit) { BackHandler(enabled, onBack) }

// activity 1.13 의 BackHandler 는 NavigationEvent 디스패처에 붙는다. 안쪽에 자식 디스패처를 두고 [enabled] 동안 꺼서
// 시트 뒤 화면의 뒤로 가기 처리가 이벤트를 받지 못하게 한다. 자식 디스패처는 그대로라 안쪽 핸들러를 다시 등록하지 않는다
@Composable internal actual fun NoBackHandling(enabled: Boolean, content: @Composable () -> Unit) {
    if (LocalNavigationEventDispatcherOwner.current == null) { content(); return }   // Preview 등 디스패처가 없는 곳
    val owner = rememberNavigationEventDispatcherOwner(enabled = !enabled)
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) { content() }
}

// 앱은 어두운 아이콘으로 고정돼 있다(MainActivity). 떠날 때는 들어올 때의 값으로 되돌린다
@Composable internal actual fun LightStatusBarIcons(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val window = view.context.activity()?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val before = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = before }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
