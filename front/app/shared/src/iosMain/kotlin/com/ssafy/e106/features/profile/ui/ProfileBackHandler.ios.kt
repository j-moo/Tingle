package com.ssafy.e106.features.profile.ui

import androidx.compose.runtime.Composable

// iOS has no Android system back event. The visible back button uses the same callback.
@Composable internal actual fun ProfileBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@Composable internal actual fun NoBackHandling(enabled: Boolean, content: @Composable () -> Unit) = content()
