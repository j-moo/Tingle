package com.ssafy.e106.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * visionOS 26 UI Kit(Apple Design Resources) 값. 유리 면 · 큰 모서리 · 떠 있는 탭 바 모양은 키트를 따르고, 색은 11-16 결정으로 밝게: 공통 배경([GlassBackdrop]) 위에 떠 있는 반투명 흰 판, 어두운 글자. 그림자는 두지 않는다.
 * 뒤 배경 블러(Haze)는 떠 있는 탭 바에만 켠다([frost] 참고). 나머지 면은 거의 불투명한 흰색이라 블러 없이 반투명 색만 칠한다
 */
object AppColors {
    /** 화면 바닥. 투명이라 [GlassBackdrop] 이 비친다. 웹 화면 같은 다른 내용을 덮는 판에는 쓰지 않는다([Sheet]) */
    val Screen = Color.Transparent
    /** 뒤가 비치면 안 되는 면: 바텀시트 · 대화상자 */
    val Sheet = Color(0xFFF5F5F5)
    /** 유리 면(카드 · 입력창 · 떠 있는 바) */
    val Background = Color(0xF2FFFFFF)
    val SurfaceMuted = Color(0xE6FFFFFF)
    val TextPrimary = Color(0xFF1A1A1A)
    val TextSecondary = Color(0xFF555555)
    val ActionPrimary = Color(0xFF171717)
    /** 유리 버튼을 누르고 있는 동안의 바탕(회색 60%) */
    val Pressed = Color(0x99808080)
    val OnPrimary = Color(0xFFFFFFFF)
    val BorderControl = Color(0xFF767676)
    val Error = Color(0xFFB3261E)
    val OnError = Color(0xFFFFFFFF)
    val ActionDisabled = Color(0xFFE5E5E5)
    val TextDisabled = Color(0xFF555555)
    val SelectionContainer = Color(0xFFE0E0E0)
    val OnSelection = Color(0xFF171717)
    val SelectionBorder = Color(0xFF171717)
    /** 구분선·카드 테두리·비활성 탭 캡슐 바탕 · 꺼진 홈 원 (visionOS 키트 기준 옅은 검정) */
    val BorderSubtle = Color(0x1F1A1A1A)
    /** 되돌릴 수 없는 작업(키링 초기화)의 경고 배경 · 글자 */
    val ErrorContainer = Color(0xFFFDECEA)
    val OnErrorContainer = Color(0xFF6B1510)
    /** 켜진 홈 원 글자 · 생활 정보 아이콘의 연두 잉크(연두 바탕 대비 4.5:1 이상) */
    val FreshInk = Color(0xFF2F6B12)
}
