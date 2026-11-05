package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSizes
import com.ssafy.e106.core.ui.theme.AppSpacing

/** 하단 내비게이션. 글자 탭을 같은 폭으로 늘어놓는다. Figma 01 Tingle/BottomNavigation. 최소 높이 72, 위 구분선 1. */
@Composable
fun AppBottomBar(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AppTabRow(
        labels = labels, selected = selected, onSelect = onSelect,
        modifier = modifier.heightIn(min = AppSizes.BottomBarMinHeight)
            .background(AppColors.Background)
            .drawBehind {
                val stroke = AppSpacing.Stroke.toPx()
                drawLine(AppColors.BorderSubtle, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
            }
            .padding(horizontal = AppSpacing.Medium)
            .wrapContentHeight(),
    )
}
