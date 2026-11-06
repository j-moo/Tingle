package com.ssafy.e106.features.onboarding.ui

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSizes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.resources.Res
import com.ssafy.e106.resources.onboarding_read_terms
import org.jetbrains.compose.resources.stringResource

/**
 * 약관 한 줄: 왼쪽 체크(체크되면 채워진 네모 + 흰 체크) · 가운데 제목 · 오른쪽 "내용 보기".
 * 체크 영역(네모 + 제목)을 눌러 체크한다. [onRead] 가 없으면(전문 없음 · 전체동의 줄) 내용 보기를 그리지 않는다.
 */
@Composable
internal fun AgreementRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    strong: Boolean = false,
    onRead: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = AppSpacing.ControlMinHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = AppSpacing.ControlMinHeight)
                .toggleable(checked, enabled = enabled, role = Role.Checkbox, interactionSource = interaction,
                    indication = LocalIndication.current, onValueChange = onToggle)
                .padding(vertical = AppSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            CheckSquare(checked, enabled)
            Text(
                label, color = if (enabled) AppColors.TextPrimary else AppColors.TextDisabled,
                style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (strong) FontWeight.Bold else null, modifier = Modifier.weight(1f),
            )
        }
        if (onRead != null) TextButton(onClick = onRead) {
            Text(stringResource(Res.string.onboarding_read_terms), color = AppColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CheckSquare(checked: Boolean, enabled: Boolean) {
    val fill = when {
        checked && enabled -> AppColors.ActionPrimary
        checked -> AppColors.BorderControl
        enabled -> AppColors.Background
        else -> AppColors.ActionDisabled
    }
    val border = when {
        checked -> fill
        enabled -> AppColors.BorderControl
        else -> AppColors.BorderSubtle
    }
    Box(
        Modifier.size(AppSizes.Icon).background(fill, AppShapes.Check).border(AppSpacing.Stroke, border, AppShapes.Check)
            .drawBehind {
                if (checked) {
                    val w = size.width; val h = size.height
                    val path = Path().apply { moveTo(w * 0.24f, h * 0.52f); lineTo(w * 0.43f, h * 0.70f); lineTo(w * 0.77f, h * 0.32f) }
                    drawPath(path, AppColors.OnPrimary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                }
            },
    )
}
