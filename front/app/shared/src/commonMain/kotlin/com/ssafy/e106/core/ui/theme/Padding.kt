package com.ssafy.e106.core.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 자리별 안쪽 여백 조합. 화면 코드에 padding(horizontal = 16.dp, vertical = 12.dp) 를 직접 적지 않는다. */
object AppPadding {
    /** 카드·시트·대화상자 안쪽 */
    val Card = PaddingValues(AppSpacing.Large)
    /** 목록 한 줄 */
    val ListItem = PaddingValues(horizontal = AppSpacing.Large, vertical = AppSpacing.Medium)
    /** 선택지·태그·작은 버튼 */
    val Chip = PaddingValues(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small)
    /** 화면 위아래 */
    val ScreenVertical = AppSpacing.Section
}

/**
 * 화면 가장자리 좌우 여백. 창 폭 기준으로 넓어진다 (Material3 window size class 경계 600 · 840 dp).
 * 세로 휴대폰은 16, 태블릿·폴더블 펼침은 24 · 32. 휴대폰도 가로 모드로 폭이 840 을 넘으면 32 가 된다(M3 규칙 그대로).
 */
@Composable
fun screenGutter(): Dp {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return when {
        width < 600.dp -> AppSpacing.Large
        width < 840.dp -> AppSpacing.Section
        else -> AppSpacing.ExtraLarge
    }
}

/**
 * 화면 루트 여백. 좌우는 [screenGutter], 위아래는 [AppPadding.ScreenVertical].
 * 스크롤 화면은 `LazyColumn(contentPadding = screenPaddingValues())` 처럼 contentPadding 에 넣어야 내용이 가장자리에서 잘리지 않는다.
 */
@Composable
fun screenPaddingValues(): PaddingValues = PaddingValues(horizontal = screenGutter(), vertical = AppPadding.ScreenVertical)

/** 스크롤하지 않는 화면 루트용. `Modifier.padding(screenPaddingValues())` 과 같다. */
@Composable
fun Modifier.screenPadding(): Modifier = padding(screenPaddingValues())
