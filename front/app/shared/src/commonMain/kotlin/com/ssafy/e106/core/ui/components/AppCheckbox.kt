package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSizes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.focusRing

/** 체크박스 한 줄. 줄 전체가 터치 영역(최소 48dp)이고 선택 상태는 호출 화면이 소유한다. Figma 01 Tingle/Checkbox. */
@Composable
fun AppCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val textColor = if (enabled) AppColors.TextPrimary else AppColors.TextDisabled
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = AppSpacing.ControlMinHeight)
            .clip(AppShapes.Small).focusRing(interaction)
            .toggleable(
                value = checked, enabled = enabled, role = Role.Checkbox,
                interactionSource = interaction, indication = LocalIndication.current,
                onValueChange = onCheckedChange,
            )
            .padding(AppSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
    ) {
        Box(
            modifier = Modifier.size(AppSizes.Icon)
                .background(if (enabled) AppColors.Background else AppColors.ActionDisabled, AppShapes.Check)
                .border(AppSpacing.Stroke, if (enabled) AppColors.BorderControl else AppColors.BorderSubtle, AppShapes.Check)
                .drawBehind {
                    // 체크 표시. 아이콘 라이브러리 없이 선 두 개로 그린다
                    if (checked) {
                        val w = size.width; val h = size.height
                        val path = Path().apply {
                            moveTo(w * 0.22f, h * 0.52f); lineTo(w * 0.42f, h * 0.72f); lineTo(w * 0.78f, h * 0.32f)
                        }
                        drawPath(path, textColor, style = Stroke(width = AppSpacing.FocusStroke.toPx(), cap = StrokeCap.Round))
                    }
                },
        )
        Text(label, style = MaterialTheme.typography.bodyMedium, color = textColor, modifier = Modifier.weight(1f))
    }
}
