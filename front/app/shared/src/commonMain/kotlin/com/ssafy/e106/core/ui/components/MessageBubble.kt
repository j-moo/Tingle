package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSpacing

/**
 * 채팅 말풍선. 본문 + 시각 같은 메타. Figma 01 Tingle/Message.
 * 내 말/상대 말 색 구분은 아직 디자인이 없어 한 가지 모양만 둔다. 정렬(왼쪽·오른쪽)은 호출 화면이 한다.
 */
@Composable
fun MessageBubble(text: String, meta: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.widthIn(max = BubbleMaxWidth), shape = AppShapes.Card, color = AppColors.SurfaceMuted) {
        Column(
            Modifier.padding(horizontal = AppSpacing.Large, vertical = AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary)
            Text(meta, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
    }
}

private val BubbleMaxWidth = 286.dp
