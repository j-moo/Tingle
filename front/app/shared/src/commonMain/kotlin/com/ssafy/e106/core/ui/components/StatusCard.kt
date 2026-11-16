package com.ssafy.e106.core.ui.components

import com.ssafy.e106.core.ui.theme.frost
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.GlassBorder

/** 현재 상태와 다음 행동을 짧게 알리는 카드. Figma 01 Tingle/StatusCard. */
@Composable
fun StatusCard(title: String, detail: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth().frost(), shape = AppShapes.Card, color = AppColors.SurfaceMuted, border = GlassBorder) {
        Column(Modifier.padding(AppSpacing.Large), verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = AppColors.TextPrimary)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
        }
    }
}
