package com.ssafy.e106.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSizes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.focusRing
import com.ssafy.e106.core.ui.theme.glass

private val TabShape = RoundedCornerShape(50)

/**
 * 하단 내비게이션. 글자 탭을 같은 폭으로 늘어놓는다. 화면 아래에 떠 있는 유리 캡슐(visionOS 탭 바).
 * 선택 캡슐은 탭을 바꾸면 새 자리로 미끄러진다. [AppTabRow] 와 달리 캡슐을 탭마다 그리지 않고 줄 전체에 하나만 그린다
 */
@Composable
fun AppBottomBar(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val position by animateFloatAsState(selected.toFloat(), spring(dampingRatio = 0.7f))
    val highlight = if (enabled) AppColors.ActionPrimary.copy(alpha = 0.14f) else AppColors.BorderSubtle
    Row(
        modifier.padding(horizontal = AppSpacing.Large, vertical = AppSpacing.Small)
            .heightIn(min = AppSizes.BottomBarMinHeight - AppSpacing.Small * 2)
            .glass(RoundedCornerShape(50), blur = true)
            .padding(horizontal = AppSpacing.Small)
            .wrapContentHeight()
            .fillMaxWidth().selectableGroup()
            .drawBehind {
                if (labels.isEmpty()) return@drawBehind
                val cell = size.width / labels.size
                drawRoundRect(highlight, Offset(cell * position, 0f), Size(cell, size.height), CornerRadius(size.height / 2))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            val interaction = remember { MutableInteractionSource() }
            val color by animateColorAsState(when {
                !enabled -> AppColors.TextDisabled
                i == selected -> AppColors.TextPrimary
                else -> AppColors.TextSecondary
            })
            Box(
                Modifier.weight(1f).heightIn(min = AppSpacing.ControlMinHeight)
                    .clip(TabShape)
                    .focusRing(interaction, TabShape)
                    .selectable(selected = i == selected, enabled = enabled, role = Role.Tab,
                        interactionSource = interaction, indication = LocalIndication.current, onClick = { onSelect(i) })
                    .padding(AppSpacing.Small),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
            }
        }
    }
}
