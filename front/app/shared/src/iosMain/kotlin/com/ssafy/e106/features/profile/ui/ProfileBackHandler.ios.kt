package com.ssafy.e106.features.profile.ui

import androidx.compose.runtime.Composable

// iOS has no Android system back event. The visible back button uses the same callback.
@Composable internal actual fun ProfileBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@Composable internal actual fun NoBackHandling(enabled: Boolean, content: @Composable () -> Unit) = content()

// iOS 상태 바 글자색은 화면 컨트롤러(UIViewController)가 정한다. 아직 iOS 앱 틀이 없어 비워 둔다
@Composable internal actual fun LightStatusBarIcons(enabled: Boolean) = Unit
