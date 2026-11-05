package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSpacing

/** 사진이 없을 때 자리. Figma 01 Tingle/ProfilePlaceholder. 높이 144. */
@Composable
fun ProfilePlaceholder(text: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth().height(PlaceholderHeight), shape = AppShapes.Card, color = AppColors.SurfaceMuted) {
        Box(Modifier.padding(AppSpacing.Section), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
        }
    }
}

private val PlaceholderHeight = 144.dp
