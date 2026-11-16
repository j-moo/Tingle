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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.components.InlineError
import com.ssafy.e106.core.ui.components.KeyringPebbleColors
import com.ssafy.e106.core.ui.components.NoticeBar
import com.ssafy.e106.core.ui.components.PullDownRefreshBox
import com.ssafy.e106.core.ui.components.SkeletonBone
import com.ssafy.e106.core.ui.components.SkeletonGroup
import com.ssafy.e106.core.ui.components.drawKeyringPebble
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.screenPaddingValues
import com.ssafy.e106.resources.Res
import com.ssafy.e106.resources.account_withdrawal
import com.ssafy.e106.resources.onboarding_keyring_body
import com.ssafy.e106.resources.onboarding_keyring_buy
import com.ssafy.e106.resources.onboarding_keyring_find
import com.ssafy.e106.resources.onboarding_keyring_no
import com.ssafy.e106.resources.onboarding_keyring_title
import com.ssafy.e106.resources.onboarding_link_failed
import com.ssafy.e106.resources.onboarding_logout
import com.ssafy.e106.resources.onboarding_refresh
import org.jetbrains.compose.resources.stringResource

/**
 * 가입 키링 단계 첫 화면(시안 02): 가운데 조약돌 키링에서 신호가 퍼지고, 그 아래 제목 · 안내, 맨 아래 키링 찾기 · 구매 안내.
 * [onFind] 는 키링 연결 화면을 연다(운영 앱 · 개발 앱 같음). 없으면(Preview 등) 버튼만 막는다.
 * 구매 안내를 받는 중([busy])엔 그 자리만 뼈대로 두고, 못 받아도([error]) 키링 등록은 할 수 있게 화면은 그대로 둔 채 아래 띠로 알린다.
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
    /** 가입을 마친 뒤 키링을 모두 초기화해 이 화면에 온 계정만: 탈퇴는 키링이 없어야 할 수 있어 여기서도 열어 준다 */
    onWithdraw: (() -> Unit)? = null,
) {
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            val (refreshing, pull) = pullRefresh(busy, onReload)
            PullDownRefreshBox(refreshing, pull, Modifier.weight(1f).fillMaxWidth()) {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
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
                        Spacer(Modifier.weight(1f))
                        if (linkFailed) InlineError(stringResource(Res.string.onboarding_link_failed))
                        AppButton(stringResource(Res.string.onboarding_keyring_find), Modifier.fillMaxWidth(), enabled = onFind != null && !busy,
                            onClick = { onFind?.invoke() })
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
                        // 구매 안내를 받는 중: 그 자리만 뼈대
                        if (purchaseUrl == null && busy) SkeletonGroup(Modifier.padding(vertical = AppSpacing.Large)) {
                            SkeletonBone(Modifier.align(Alignment.CenterHorizontally), width = 160.dp, height = 14.dp)
                        }
                        // 서버에서 가입 단계가 바뀌었는지 다시 확인(운영 앱은 키링 찾기가 막혀 있어 이것 말고는 갱신할 방법이 없다)
                        TextButton(onClick = onRefresh, enabled = !busy) {
                            Text(stringResource(Res.string.onboarding_refresh), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        }
                        TextButton(onClick = onLogout) {   // 불러오는 중에도 나갈 수 있어야 한다(로그아웃은 진행 중 작업을 취소한다)
                            Text(stringResource(Res.string.onboarding_logout), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        }
                        onWithdraw?.let { withdraw ->
                            TextButton(onClick = withdraw, enabled = !busy) {
                                Text(stringResource(Res.string.account_withdrawal), style = MaterialTheme.typography.bodySmall, color = AppColors.Error)
                            }
                        }
                    }
                }
            }
            // 구매 안내 · 가입 상태를 못 받음: 화면은 그대로 두고 아래 띠로 알린다
            if (error != null) NoticeBar(error, Modifier.widthIn(max = 640.dp), actionEnabled = !busy, onAction = onReload)
        }
    }
}

/** 아이보리 조약돌 키링 + 고리 + 민트 불빛, 바깥으로 퍼지는 신호 세 겹(장식이라 읽지 않음) */
@Composable
private fun SignalPebble(modifier: Modifier = Modifier) {
    // 프리뷰는 화면이 멈출 때까지 기다렸다 찍으므로 무한 애니메이션이면 끝나지 않는다. 프리뷰에선 한 장면으로 고정
    val anim = if (LocalInspectionMode.current) null
        else rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart))
    Canvas(modifier) {
        val wave = anim?.value ?: 0.3f                     // 그리기 단계에서만 읽는다
        val c = center
        val maxR = size.minDimension / 2
        repeat(3) { i ->
            val t = (wave + i / 3f) % 1f
            drawCircle(KeyringPebbleColors.Signal.copy(alpha = 0.9f * (1 - t)), radius = maxR * (0.32f + 0.68f * t), center = c, style = Stroke(1.5.dp.toPx()))
        }
        drawKeyringPebble(c, maxR * 0.62f)
    }
}
