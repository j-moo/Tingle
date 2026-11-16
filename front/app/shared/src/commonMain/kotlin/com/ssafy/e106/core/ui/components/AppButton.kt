package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.Res
import com.ssafy.e106.resources.button_loading
import org.jetbrains.compose.resources.stringResource

enum class AppButtonVariant { Primary, Secondary, Danger, Selected }

/** State belongs to the caller. This component never starts a request or a timer. */
@Composable
fun AppButton(
    text: String,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingText: String = stringResource(Res.string.button_loading),
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val clickable = enabled && !loading
    val pressed by interaction.collectIsPressedAsState()
    val container = when (variant) {
        AppButtonVariant.Primary -> colors.primary.copy(alpha = 0.72f)
        AppButtonVariant.Secondary -> colors.surface
        AppButtonVariant.Danger -> colors.error.copy(alpha = 0.8f)
        AppButtonVariant.Selected -> AppColors.SelectionContainer
    }
    val foreground = when (variant) {
        AppButtonVariant.Primary -> colors.onPrimary
        AppButtonVariant.Secondary -> colors.onSurface
        AppButtonVariant.Danger -> colors.onError
        AppButtonVariant.Selected -> AppColors.OnSelection
    }
    Button(
        // Keep the guard as well as disabled semantics for already-dispatched clicks.
        onClick = { if (clickable) onClick() },
        enabled = clickable,
        modifier = modifier.heightIn(min = AppSpacing.ControlMinHeight)
            .frost(AppShapes.Control)
            .focusRing(interaction, AppShapes.Control)
            .semantics {
                role = Role.Button
                if (loading) {
                    stateDescription = loadingText
                }
            },
        shape = AppShapes.Control,
        interactionSource = interaction,
        elevation = null,
        border = when {
            variant == AppButtonVariant.Selected -> BorderStroke(AppSpacing.FocusStroke, if (enabled) AppColors.SelectionBorder else AppColors.BorderSubtle)
            variant == AppButtonVariant.Secondary && (clickable || loading) -> BorderStroke(AppSpacing.Stroke, colors.outline)
            else -> null
        },
        colors = ButtonDefaults.buttonColors(
            // 누르는 동안 회색은 밝은 버튼에만. 흰 글자 버튼(Primary · Danger)은 바탕을 그대로 둬 글자가 묻히지 않게 한다
            containerColor = if (pressed && variant in PressedGrayVariants) AppColors.Pressed else container, contentColor = foreground,
            disabledContainerColor = if (loading) container else AppColors.ActionDisabled,
            disabledContentColor = if (loading) foreground else AppColors.TextDisabled,
        ),
        contentPadding = PaddingValues(horizontal = AppSpacing.Large, vertical = AppSpacing.Medium),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Reserve the normal label's size without exposing duplicate accessibility text.
            Text(text, modifier = Modifier.alpha(0f).clearAndSetSemantics {},
                style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                if (loading) CircularProgressIndicator(
                    modifier = Modifier.size(AppSpacing.ProgressSize).clearAndSetSemantics {},
                    color = foreground, strokeWidth = AppSpacing.FocusStroke,
                )
                Text(if (loading) loadingText else text,
                    style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
        }
    }
}

private val PressedGrayVariants = setOf(AppButtonVariant.Secondary, AppButtonVariant.Selected)
