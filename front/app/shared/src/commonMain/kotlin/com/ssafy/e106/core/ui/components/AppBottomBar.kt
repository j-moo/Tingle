package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ssafy.e106.core.ui.theme.AppSizes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.glass

/** 하단 내비게이션. 글자 탭을 같은 폭으로 늘어놓는다. 화면 아래에 떠 있는 유리 캡슐(visionOS 탭 바). */
@Composable
fun AppBottomBar(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AppTabRow(
        labels = labels, selected = selected, onSelect = onSelect,
        modifier = modifier.padding(horizontal = AppSpacing.Large, vertical = AppSpacing.Small)
            .heightIn(min = AppSizes.BottomBarMinHeight - AppSpacing.Small * 2)
            .glass(RoundedCornerShape(50), blur = true)
            .padding(horizontal = AppSpacing.Small)
            .wrapContentHeight(),
    )
}
