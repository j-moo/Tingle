package com.ssafy.e106.features.profile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable internal actual fun ProfileBackHandler(enabled: Boolean, onBack: () -> Unit) { BackHandler(enabled, onBack) }
