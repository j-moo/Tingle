package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.Role
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.focusRing

private val TabShape = RoundedCornerShape(50)

/** 글자 탭 하나. 선택됨은 진한 글자와 옅은 캡슐 바탕으로 표시한다. */
@Composable
fun AppTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val textColor = when {
        !enabled -> AppColors.TextDisabled
        selected -> AppColors.TextPrimary
        else -> AppColors.TextSecondary
    }
    val highlight = if (enabled) AppColors.ActionPrimary.copy(alpha = 0.14f) else AppColors.BorderSubtle
    Box(
        modifier = modifier.heightIn(min = AppSpacing.ControlMinHeight)
            .clip(TabShape)
            .focusRing(interaction, TabShape)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab,
                interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .drawBehind { if (selected) drawRoundRect(highlight, cornerRadius = CornerRadius(size.height / 2)) }
            .padding(AppSpacing.Small),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = textColor, maxLines = 1)
    }
}

/** 같은 폭으로 늘어선 탭 묶음. 선택 index 는 호출 화면이 소유한다. */
@Composable
fun AppTabRow(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, label ->
            AppTab(label, selected = i == selected, onClick = { onSelect(i) }, enabled = enabled, modifier = Modifier.weight(1f))
        }
    }
}
