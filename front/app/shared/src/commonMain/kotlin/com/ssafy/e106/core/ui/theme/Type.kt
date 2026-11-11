package com.ssafy.e106.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.ssafy.e106.resources.Res
import com.ssafy.e106.resources.pretendard_bold
import com.ssafy.e106.resources.pretendard_medium
import com.ssafy.e106.resources.pretendard_regular
import org.jetbrains.compose.resources.Font

/**
 * Pretendard 정적 글꼴 3굵기 (OFL). Figma 07 은 Noto Sans KR 로 그려져 있으나 10-29 결정으로 Pretendard 로 바꿈.
 * Material3 Typography 15슬롯 전부에 글꼴을 입힌다. 5슬롯만 바꾸면 NavigationBar 라벨·AlertDialog 제목 등이 Roboto 로 섞이고,
 * TextStyle 을 새로 만들면 M3 의 lineHeightStyle(Center · Trim.None)을 잃어 한글이 줄 안에서 위로 쏠린다. 그래서 기본 슬롯을 copy 한다.
 * 크기·줄 높이를 따로 정한 슬롯은 Figma 07 의 5단(제목 22/30 · 본문 16/26 · 설명 14/22 · 버튼 14/20 · 도움말 12/18)이다.
 * 줄바꿈은 글자 단위가 아니라 어절 단위(Phrase)로: "수락한 뒤에는 취소할 수 없어|요"처럼 낱말이 쪼개지지 않게. 문단은 줄 길이를 고르게(Paragraph).
 * ponytail: 어절 단위는 Android 13(API 33)+ 에서만 동작, 그 아래는 기존처럼 글자 단위. WordBreak 는 Android 전용 API 라 플랫폼별로 둔다(iOS 는 Paragraph 만)
 */
@Composable
fun appTypography(): Typography {
    val font = FontFamily(
        Font(Res.font.pretendard_regular, FontWeight.Normal),
        Font(Res.font.pretendard_medium, FontWeight.Medium),
        Font(Res.font.pretendard_bold, FontWeight.Bold),
    )
    return remember(font) {
        val base = Typography()
        fun TextStyle.app(size: TextUnit = fontSize, line: TextUnit = lineHeight, weight: FontWeight? = fontWeight) =
            copy(fontFamily = font, fontSize = size, lineHeight = line, fontWeight = weight,
                lineBreak = AppLineBreak)
        base.copy(
            displayLarge = base.displayLarge.app(), displayMedium = base.displayMedium.app(), displaySmall = base.displaySmall.app(),
            headlineLarge = base.headlineLarge.app(), headlineMedium = base.headlineMedium.app(), headlineSmall = base.headlineSmall.app(),
            titleLarge = base.titleLarge.app(22.sp, 30.sp, FontWeight.Bold), titleMedium = base.titleMedium.app(), titleSmall = base.titleSmall.app(),
            bodyLarge = base.bodyLarge.app(16.sp, 26.sp), bodyMedium = base.bodyMedium.app(14.sp, 22.sp), bodySmall = base.bodySmall.app(12.sp, 18.sp),
            labelLarge = base.labelLarge.app(14.sp, 20.sp, FontWeight.Medium), labelMedium = base.labelMedium.app(), labelSmall = base.labelSmall.app(),
        )
    }
}

/** 줄바꿈 방식. Android 는 어절(Phrase) 단위, iOS 는 문단 단위만(WordBreak 가 Android 전용) */
internal expect val AppLineBreak: LineBreak
