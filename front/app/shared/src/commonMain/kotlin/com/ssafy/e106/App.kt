package com.ssafy.e106

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.ssafy.e106.core.ui.theme.GlassBackdrop
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppTheme

@Composable
fun App(content: @Composable () -> Unit = { ComponentGallery() }) {
    AppTheme {
        GlassBackdrop {
            CompositionLocalProvider(LocalContentColor provides AppColors.TextPrimary) { content() }
        }
    }
}
