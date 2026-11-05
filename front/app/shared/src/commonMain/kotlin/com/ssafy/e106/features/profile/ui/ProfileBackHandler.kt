package com.ssafy.e106.features.profile.ui

import androidx.compose.runtime.Composable

@Composable internal expect fun ProfileBackHandler(enabled: Boolean, onBack: () -> Unit)
