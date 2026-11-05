package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.focusRing

/**
 * 스위치 한 줄. 줄 전체가 터치 영역이고 ON/OFF 는 위치와 명도로 구분한다. Figma 01 Tingle/Switch.
 * 저장 중 중복 변경을 막고 실패 시 되돌리는 것은 호출 화면의 몫이다(enabled=false 로 잠근다).
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = AppSpacing.ControlMinHeight)
            .clip(AppShapes.Small).focusRing(interaction)
            .toggleable(
                value = checked, enabled = enabled, role = Role.Switch,
                interactionSource = interaction, indication = LocalIndication.current,
                onValueChange = onCheckedChange,
            )
            .padding(AppSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) AppColors.TextPrimary else AppColors.TextDisabled, modifier = Modifier.weight(1f))
        // onCheckedChange=null: 줄 전체의 toggleable 이 처리한다. interactionSource 를 같이 넘겨 줄을 누를 때 썸도 눌림 표시가 난다
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled, interactionSource = interaction,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppColors.OnPrimary, checkedTrackColor = AppColors.TextPrimary, checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = AppColors.TextSecondary, uncheckedTrackColor = AppColors.Background, uncheckedBorderColor = AppColors.BorderControl,
                disabledCheckedThumbColor = AppColors.TextSecondary, disabledCheckedTrackColor = AppColors.ActionDisabled, disabledCheckedBorderColor = Color.Transparent,
                disabledUncheckedThumbColor = AppColors.TextSecondary, disabledUncheckedTrackColor = AppColors.ActionDisabled, disabledUncheckedBorderColor = AppColors.BorderSubtle,
            ),
        )
    }
}
