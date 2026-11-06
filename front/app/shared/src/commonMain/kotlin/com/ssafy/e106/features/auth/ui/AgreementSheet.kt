package com.ssafy.e106.features.auth.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.resources.Res
import com.ssafy.e106.resources.agreement_sheet_close
import org.jetbrains.compose.resources.stringResource

/**
 * 로그인 직후 약관 동의를 로그인 화면 위에 아래에서 올라오는 시트로 띄운다. 뒤의 로그인 화면은 어둡게 덮어 누를 수 없다.
 * ✕ · 시스템 뒤로 가기 = [onClose](로그아웃). 시트를 끌어내려 닫는 동작은 없다(동의 없이 다음 단계로 갈 수 없으므로 닫기 = 로그인 취소).
 */
@Composable
fun AgreementSheet(onClose: () -> Unit, background: @Composable () -> Unit, animate: Boolean = true, content: @Composable () -> Unit) {
    val shown = remember { MutableTransitionState(!animate).apply { targetState = true } }   // animate=false: 처음부터 올라온 상태(정지 Preview 용)
    ProfileBackHandler(enabled = true, onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        background()
        AnimatedVisibility(shown, enter = fadeIn()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {})   // 뒤 로그인 화면 터치 막기
        }
        AnimatedVisibility(shown, Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it }) {
            Surface(
                Modifier.fillMaxWidth().fillMaxHeight(0.92f)
                    .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),   // 시트는 상태 표시줄 아래라 안쪽 화면이 위 여백을 또 넣지 않게
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = AppColors.Background,
            ) {
                Column {
                    val close = stringResource(Res.string.agreement_sheet_close)
                    Box(Modifier.align(Alignment.End).padding(top = AppSpacing.Small, end = AppSpacing.Small).size(48.dp)
                        .clickable(role = Role.Button, onClick = onClose).semantics { contentDescription = close },
                        contentAlignment = Alignment.Center) {
                        Text("✕", fontSize = 20.sp, color = AppColors.TextPrimary)
                    }
                    Box(Modifier.weight(1f)) { content() }
                }
            }
        }
    }
}
