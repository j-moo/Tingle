package com.ssafy.e106.features.startup.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.resources.Res
import com.ssafy.e106.resources.startup_logo_description
import com.ssafy.e106.resources.tingle_wordmark_cream
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

// Figma 152:6의 로고 전용 배경. 공통 앱 테마의 배경색을 변경하지 않는다.
private val WordmarkBackground = Color(0xFFF5F0E7)
private val WordmarkFrameMaxSize = 320.dp

/**
 * 앱 내부 시작 화면의 디자인. 세션 확인·타이머·화면 이동을 실행하지 않는다.
 * 운영체제 시작 화면과의 연결은 순철 인증 기반 및 NavHost 통합 때 별도로 처리한다.
 */
@Composable
fun StartupScreen(modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier.fillMaxSize().background(WordmarkBackground).safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        val logoSize = minOf(
            (maxWidth - AppSpacing.Section * 2).coerceAtLeast(0.dp),
            maxHeight,
            WordmarkFrameMaxSize,
        )
        Image(
            painter = painterResource(Res.drawable.tingle_wordmark_cream),
            contentDescription = stringResource(Res.string.startup_logo_description),
            modifier = Modifier.size(logoSize),
            contentScale = ContentScale.Fit,
        )
    }
}
