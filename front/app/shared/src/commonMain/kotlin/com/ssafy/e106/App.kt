package com.ssafy.e106

import androidx.compose.runtime.Composable
import com.ssafy.e106.core.ui.theme.AppTheme

@Composable
fun App(content: @Composable () -> Unit = { ComponentGallery() }) {
    AppTheme { content() }
}
