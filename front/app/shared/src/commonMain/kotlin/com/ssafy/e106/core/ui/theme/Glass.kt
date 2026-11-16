package com.ssafy.e106.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** 유리 면이 흐리게 비출 배경. [GlassBackdrop] 밖(미리보기)에서는 없고, 그때 유리 면은 반투명 색만 칠한다. 블러를 켜는 곳은 떠 있는 탭 바뿐이다. */
private val LocalGlassSource = staticCompositionLocalOf<HazeState?> { null }

private val GlassBlur = HazeStyle(backgroundColor = Color.Transparent, tints = emptyList(), blurRadius = 22.dp, noiseFactor = 0f)

/** 앱 전체 바닥: 흰색에서 연한 회색으로 흐르는 바탕 + 회색 · 흰색 얼룩 여섯 개(왼쪽 위 하나만 연분홍). 얼룩이 있어야 유리 면 뒤가 비쳐 보인다. */
@Composable
fun GlassBackdrop(content: @Composable () -> Unit) {
    val source = rememberHazeState()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(source).drawBehind {
            drawRect(Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF2F2F2), Color(0xFFE4E4E4))))
            fun glow(color: Color, x: Float, y: Float, r: Float) {
                val center = Offset(size.width * x, size.height * y)
                val radius = size.width * r
                drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center, radius), radius, center)
            }
            glow(Color(0x33FF9CC0), 0.02f, 0.14f, 0.55f)   // 왼쪽 위만 연분홍
            glow(Color(0xFFFFFFFF), 0.95f, 0.30f, 0.50f)
            glow(Color(0x33606060), 0.70f, 0.56f, 0.42f)
            glow(Color(0xFFFFFFFF), 0.18f, 0.50f, 0.40f)
            glow(Color(0x33505050), 0.15f, 0.82f, 0.50f)
            glow(Color(0xFFFFFFFF), 0.85f, 0.90f, 0.45f)
        })
        CompositionLocalProvider(LocalGlassSource provides source) { content() }
    }
}

/** 이 안의 내용도 떠 있는 탭 바 뒤에서 흐리게 비친다(앱 바닥 위에 겹쳐 그린다). 탭 바를 품은 틀의 내용 영역에만 붙인다 */
@Composable
fun Modifier.glassLayer(): Modifier = LocalGlassSource.current?.let { hazeSource(it, zIndex = 1f) } ?: this

/** 블러를 켠 면 뒤가 실제로 흐려지는지. [GlassBackdrop] 밖(미리보기)이나 블러를 못 하는 기기(Android 12 미만)에서는 false → 덮는 색을 더 진하게 한다 */
@Composable
fun glassBlurAvailable(): Boolean = LocalGlassSource.current != null && HazeDefaults.blurEnabled()

/**
 * 유리 면 자리를 [shape] 모양으로 잘라 내고, [blur] 면 뒤 배경도 흐린다. 색 · 테두리는 칠하지 않는다(Surface 처럼 색을 따로 칠하는 곳에 붙인다).
 * 블러는 위치가 바뀔 때마다 다시 그려 목록 · 칩처럼 많이 · 자주 움직이는 곳에서는 비싸다. 덮는 색이 거의 불투명해
 * 흐림이 잘 보이지 않으므로 기본은 끄고, 떠 있는 탭 바만 켠다. 블러를 꺼도 잘라 내기는 한다(호출부가 모양을 여기에 맡긴다).
 */
@Composable
fun Modifier.frost(shape: Shape = AppShapes.Card, blur: Boolean = false): Modifier {
    val source = LocalGlassSource.current?.takeIf { blur } ?: return clip(shape)
    return clip(shape).hazeEffect(source, GlassBlur)
}

/** 유리 면: (켜면) 뒤 배경 블러 + 반투명 흰 바탕 + 밝은 가장자리. 그림자는 두지 않는다(11-16 결정). */
@Composable
fun Modifier.glass(shape: Shape = AppShapes.Card, fill: Color = AppColors.Background, blur: Boolean = false): Modifier =
    frost(shape, blur).background(fill, shape).border(GlassBorder, shape)
