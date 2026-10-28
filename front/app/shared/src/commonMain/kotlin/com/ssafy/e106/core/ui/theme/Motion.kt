package com.ssafy.e106.core.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing

/** 애니메이션 시간(ms)과 이징. 화면 코드에 tween(300) 같은 숫자를 직접 적지 않는다. */
object AppMotion {
    /** 눌림·토글·색 변화 같은 짧은 피드백 */
    const val SHORT_MS = 100
    /** 레이어 오르내림, 펼침·접힘 */
    const val MEDIUM_MS = 200
    /** 화면 전환, 바텀시트 등장 */
    const val LONG_MS = 300

    /** 기본. 들어올 때와 나갈 때 모두 */
    val Standard: Easing = FastOutSlowInEasing
    /** 들어오는 것(등장)에 */
    val Enter: Easing = LinearOutSlowInEasing
    /** 나가는 것(사라짐)에. 빠르게 시작해 끝까지 간다 */
    val Exit: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}
