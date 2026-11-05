package com.ssafy.e106.core.ui.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 레이어 5단계. 숫자가 클수록 위에 뜬다.
 * - elevation: 그림자 높이
 * - scrimAlpha: 이 레이어가 뜰 때 뒤 배경을 어둡게 하는 정도 (0 = 안 함)
 * - blur: 이 레이어가 뜰 때 뒤 배경을 흐리는 반경 (0 = 안 함). Android 12 미만은 블러를 무시하고 스크림만 적용된다.
 */
enum class AppLayer(val elevation: Dp, val scrimAlpha: Float, val blur: Dp) {
    Base(0.dp, 0f, 0.dp),        // 화면 바닥
    Card(2.dp, 0f, 0.dp),        // 목록 항목, 카드
    Raised(4.dp, 0f, 0.dp),      // 앱바, 떠 있는 버튼
    Sheet(8.dp, 0.24f, 2.dp),    // 바텀시트, 메뉴
    Dialog(16.dp, 0.40f, 4.dp),  // 대화상자, 전체 덮는 안내
}
// AppDialog 는 플랫폼 Dialog 창을 쓰므로 뒤 배경 스크림은 창이 담당하고 behindLayer 를 붙이지 않는다.
// Dialog 의 scrimAlpha·blur 는 인트리로 전체를 덮는 안내(바텀시트 등)에 behindLayer 를 쓸 때의 값이다.

/** 키보드·D-pad 포커스가 오면 2dp 진한 테두리를 그린다. 체크박스·스위치·라디오·탭 공통. */
@androidx.compose.runtime.Composable
fun Modifier.focusRing(interaction: InteractionSource, shape: Shape = AppShapes.Small): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    return border(AppSpacing.FocusStroke, if (focused) AppColors.TextPrimary else Color.Transparent, shape)
}

/** 그림자 색. 흰 바닥 위 흰 카드도 떠 보이도록 시스템 기본보다 진하다. */
private val ShadowColor = AppColors.TextPrimary.copy(alpha = 0.28f)

/** 이 요소를 [layer] 높이로 띄운다. layer 가 바뀌면 그림자가 부드럽게 움직인다. */
@androidx.compose.runtime.Composable
fun Modifier.appLayer(layer: AppLayer, shape: androidx.compose.ui.graphics.Shape = AppShapes.Control): Modifier {
    val elevation by animateDpAsState(layer.elevation, tween(AppMotion.MEDIUM_MS, easing = AppMotion.Standard), label = "layerElevation")
    if (elevation <= 0.dp) return this
    // 높이의 3배만큼 퍼지고 높이만큼 아래로 치우친다
    return dropShadow(shape, Shadow(radius = elevation * 3, color = ShadowColor, offset = DpOffset(0.dp, elevation)))
}

/**
 * [layer] 가 뜬 동안 그 뒤에 깔리는 내용에 붙인다. layer 가 Sheet 이상이면 흐려지고 어두워지며,
 * Base 로 돌아오면 같은 시간에 걸쳐 원래대로 돌아온다.
 */
@androidx.compose.runtime.Composable
fun Modifier.behindLayer(layer: AppLayer): Modifier {
    val alpha by animateFloatAsState(layer.scrimAlpha, tween(AppMotion.MEDIUM_MS, easing = AppMotion.Standard), label = "layerScrim")
    val radius by animateDpAsState(layer.blur, tween(AppMotion.MEDIUM_MS, easing = AppMotion.Standard), label = "layerBlur")
    return blur(radius).drawWithContent {
        drawContent()
        if (alpha > 0f) drawRect(AppColors.TextPrimary.copy(alpha = alpha))
    }
}
