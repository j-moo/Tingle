package com.ssafy.e106.features.onboarding.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.components.AppButtonVariant
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.screenPaddingValues
import com.ssafy.e106.resources.Res
import com.ssafy.e106.resources.onboarding_keyring_body
import com.ssafy.e106.resources.onboarding_keyring_buy
import com.ssafy.e106.resources.onboarding_keyring_find
import com.ssafy.e106.resources.onboarding_keyring_no
import com.ssafy.e106.resources.onboarding_keyring_search
import com.ssafy.e106.resources.onboarding_keyring_title
import com.ssafy.e106.resources.onboarding_link_failed
import com.ssafy.e106.resources.onboarding_logout
import com.ssafy.e106.resources.onboarding_refresh
import com.ssafy.e106.resources.onboarding_retry
import org.jetbrains.compose.resources.stringResource

private object Pebble {
    val Light = Color(0xFFFFFDF8)
    val Body = Color(0xFFF1ECE1)
    val Shade = Color(0xFFE5DDCD)
    val Ring = Color(0xFFB9B2A3)
    val Signal = Color(0xFFA8C8B3)                        // 민트 — 키링 불빛 · 신호
}

/**
 * 가입 키링 단계 첫 화면(시안 02): 가운데 조약돌 키링에서 신호가 퍼지고, 그 아래 제목 · 안내, 맨 아래 키링 찾기 · 구매 안내.
 * [onFind] 가 없으면(운영 앱 — 아직 사용자용 등록 연결 전) 버튼을 "준비 중"으로 막는다. 개발 앱은 키링 연결 화면을 연다.
 */
@Composable
internal fun KeyringIntro(
    busy: Boolean,
    error: String?,
    linkFailed: Boolean,
    purchaseUrl: String?,
    onFind: (() -> Unit)?,
    onOpen: (String) -> Unit,
    onReload: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            val viewport = maxHeight
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .heightIn(min = viewport).padding(screenPaddingValues()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
            ) {
                Spacer(Modifier.weight(1f))
                SignalPebble(Modifier.size(220.dp))
                Text(stringResource(Res.string.onboarding_keyring_title), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = AppColors.TextPrimary,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(Res.string.onboarding_keyring_body), style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center, color = AppColors.TextSecondary)
                error?.let { OnboardingError(it) }
                if (linkFailed) OnboardingError(stringResource(Res.string.onboarding_link_failed))
                Spacer(Modifier.weight(1f))
                AppButton(stringResource(if (onFind != null) Res.string.onboarding_keyring_find else Res.string.onboarding_keyring_search),
                    Modifier.fillMaxWidth(), enabled = onFind != null && !busy, onClick = { onFind?.invoke() })
                purchaseUrl?.let { url ->
                    // 큰 글자에서 "구매하기"가 쪼개지지 않게 넘치면 다음 줄로
                    FlowRow(horizontalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.onboarding_keyring_no), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                        TextButton(onClick = { onOpen(url) }) {
                            Text(stringResource(Res.string.onboarding_keyring_buy), style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextPrimary, textDecoration = TextDecoration.Underline)
                        }
                    }
                }
                if (error != null) AppButton(stringResource(Res.string.onboarding_retry), Modifier.fillMaxWidth(),
                    variant = AppButtonVariant.Secondary, enabled = !busy, onClick = onReload)
                // 서버에서 가입 단계가 바뀌었는지 다시 확인(운영 앱은 키링 찾기가 막혀 있어 이것 말고는 갱신할 방법이 없다)
                TextButton(onClick = onRefresh, enabled = !busy) {
                    Text(stringResource(Res.string.onboarding_refresh), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                }
                TextButton(onClick = onLogout) {   // 불러오는 중에도 나갈 수 있어야 한다(로그아웃은 진행 중 작업을 취소한다)
                    Text(stringResource(Res.string.onboarding_logout), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                }
            }
        }
    }
}

/** 아이보리 조약돌 키링 + 고리 + 민트 불빛, 바깥으로 퍼지는 신호 세 겹(장식이라 읽지 않음) */
@Composable
private fun SignalPebble(modifier: Modifier = Modifier) {
    val wave by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart))
    Canvas(modifier) {
        val c = center
        val maxR = size.minDimension / 2
        repeat(3) { i ->
            val t = (wave + i / 3f) % 1f
            drawCircle(Pebble.Signal.copy(alpha = 0.9f * (1 - t)), radius = maxR * (0.32f + 0.68f * t), center = c, style = Stroke(1.5.dp.toPx()))
        }
        val w = maxR * 0.62f; val h = w * 0.82f
        val topLeft = Offset(c.x - w / 2, c.y - h / 2 + 6.dp.toPx())
        drawCircle(Pebble.Ring, radius = w * 0.11f, center = Offset(c.x, topLeft.y - w * 0.06f), style = Stroke(2.5.dp.toPx()))   // 고리
        drawOval(Color.Black.copy(alpha = 0.08f), topLeft + Offset(0f, 5.dp.toPx()), Size(w, h))                                     // 그림자
        drawOval(Brush.radialGradient(listOf(Pebble.Light, Pebble.Body, Pebble.Shade), center = topLeft + Offset(w * 0.35f, h * 0.3f), radius = w * 0.85f),
            topLeft, Size(w, h))
        drawCircle(Pebble.Signal, radius = 3.5.dp.toPx(), center = Offset(c.x, topLeft.y + h * 0.56f))                              // 불빛
    }
}
