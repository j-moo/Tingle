package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** 아이보리 조약돌 키링 색. 키링 화면 · 키링 연결 화면이 같은 그림을 쓴다 */
object KeyringPebbleColors {
    val Light = Color(0xFFFFFDF8)
    val Body = Color(0xFFF1ECE1)
    val Shade = Color(0xFFE5DDCD)
    val Ring = Color(0xFFB9B2A3)
    val Signal = Color(0xFFA8C8B3)                        // 민트 — 키링 불빛 · 신호
}

/** [center] 를 가운데로 폭 [width] 인 조약돌 키링(고리 · 그림자 · 몸통 · 민트 불빛)을 그린다 */
fun DrawScope.drawKeyringPebble(center: Offset, width: Float) {
    val h = width * 0.82f
    val topLeft = Offset(center.x - width / 2, center.y - h / 2 + width * 0.05f)
    drawCircle(KeyringPebbleColors.Ring, radius = width * 0.11f, center = Offset(center.x, topLeft.y - width * 0.06f),
        style = Stroke(width * 0.035f))                                                                                         // 고리
    drawOval(Color.Black.copy(alpha = 0.08f), topLeft + Offset(0f, width * 0.04f), Size(width, h))                              // 그림자
    drawOval(Brush.radialGradient(listOf(KeyringPebbleColors.Light, KeyringPebbleColors.Body, KeyringPebbleColors.Shade),
        center = topLeft + Offset(width * 0.35f, h * 0.3f), radius = width * 0.85f), topLeft, Size(width, h))
    drawCircle(KeyringPebbleColors.Signal, radius = width * 0.045f, center = Offset(center.x, topLeft.y + h * 0.56f))           // 불빛
}

/** 정지한 조약돌 키링(장식이라 읽지 않음). 크기는 [modifier] 로 정한다 */
@Composable
fun KeyringPebble(modifier: Modifier = Modifier) {
    Canvas(modifier) { drawKeyringPebble(center, size.minDimension * 0.78f) }
}
