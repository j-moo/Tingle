package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.focusRing

/** 단일 선택 항목 한 줄. 선택값은 하나뿐이며 호출 화면이 소유한다. 여러 개를 세로로 놓을 때는 부모 Column 에 Modifier.selectableGroup() 을 붙인다. Figma 01 Tingle/RadioChoice. */
@Composable
fun AppRadio(
    selected: Boolean,
    onSelect: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = AppSpacing.ControlMinHeight)
            .clip(AppShapes.Small).focusRing(interaction)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
                interactionSource = interaction, indication = LocalIndication.current, onClick = onSelect)
            .padding(AppSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
    ) {
        RadioButton(
            selected = selected, onClick = null, enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = AppColors.TextPrimary, unselectedColor = AppColors.BorderControl,
                disabledSelectedColor = AppColors.TextDisabled, disabledUnselectedColor = AppColors.BorderSubtle,
            ),
        )
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = if (enabled) AppColors.TextPrimary else AppColors.TextDisabled, modifier = Modifier.weight(1f))
    }
}
