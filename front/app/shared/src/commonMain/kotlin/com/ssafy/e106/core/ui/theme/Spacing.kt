package com.ssafy.e106.core.ui.theme

import androidx.compose.ui.unit.dp

object AppSpacing {
    val ExtraSmall = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val Section = 24.dp
    val ExtraLarge = 32.dp
    val ControlMinHeight = 48.dp
    val InputMinHeight = 56.dp
    val ProgressSize = 16.dp
    val Stroke = 1.dp
    val FocusStroke = 2.dp
}

/** 반복해서 쓰는 크기. 화면 코드에 size(24.dp) 같은 숫자를 직접 적지 않는다. */
object AppSizes {
    val IconSmall = 20.dp
    val Icon = 24.dp
    /** 목록 줄의 아바타 */
    val AvatarSmall = 40.dp
    /** 프로필 상단·제안 카드의 아바타 */
    val Avatar = 56.dp
    /** 상단 바 · 하단 바. Figma 01 ServiceHeader 64 · BottomNavigation 72. 글자 확대에 대비해 height() 가 아니라 heightIn(min) 으로 쓴다 */
    val TopBarMinHeight = 64.dp
    val BottomBarMinHeight = 72.dp
    /** 손가락으로 누르는 요소의 최소 넓이·높이 */
    val TouchTarget = 48.dp
}
