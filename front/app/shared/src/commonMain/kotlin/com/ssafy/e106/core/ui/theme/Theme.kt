package com.ssafy.e106.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val glassScheme = lightColorScheme(
    primary = AppColors.ActionPrimary, onPrimary = AppColors.OnPrimary,
    secondary = AppColors.ActionPrimary, onSecondary = AppColors.OnPrimary,
    background = AppColors.Screen, onBackground = AppColors.TextPrimary,
    surfaceContainerLowest = AppColors.Sheet, surfaceContainerLow = AppColors.Sheet, surfaceContainer = AppColors.Sheet,
    surfaceContainerHigh = AppColors.Sheet, surfaceContainerHighest = AppColors.Sheet,
    surface = AppColors.Background, onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceMuted, onSurfaceVariant = AppColors.TextSecondary,
    outline = AppColors.BorderControl,
    error = AppColors.Error, onError = AppColors.OnError,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) = ProvidePreviewResources {
    MaterialTheme(
        colorScheme = glassScheme,
        typography = appTypography(),
        shapes = Shapes(small = AppShapes.Control, medium = AppShapes.Control),
        content = content,
    )
}
