package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing

/** 설정·메뉴 목록 한 줄. 제목 + 보조 설명, 아래 구분선. 누를 수 있으면 오른쪽에 화살표를 붙인다. Figma 01 Tingle/ServiceRow. */
@Composable
fun ServiceRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    titleColor: Color = AppColors.TextPrimary,
) {
    Column(
        modifier = modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.heightIn(min = AppSpacing.ControlMinHeight).clickable(role = Role.Button, onClick = onClick) else Modifier)
            .drawBehind {
                val stroke = AppSpacing.Stroke.toPx()
                drawLine(AppColors.BorderSubtle, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
            }
            .padding(vertical = AppSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = titleColor, modifier = Modifier.weight(1f))
            // 장식이라 화면 리더에 읽히지 않게 한다
            if (onClick != null) Text(Chevron, style = MaterialTheme.typography.labelLarge, color = AppColors.TextSecondary,
                modifier = Modifier.clearAndSetSemantics {})
        }
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
    }
}

private const val Chevron = "›"
