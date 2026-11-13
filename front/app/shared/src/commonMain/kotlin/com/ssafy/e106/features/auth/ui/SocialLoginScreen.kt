package com.ssafy.e106.features.auth.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val BRAND = "tingle"   // 브랜드 이름(번역하지 않음)

// 제공자 전용 팔레트. 공통 UI 기반의 테마·버튼 API를 바꾸지 않는다.
private object LoginPalette {
    val Background = Color.White
    val GoogleSurface = Color.White
    val GoogleText = Color(0xFF1F1F1F)
    val GoogleBorder = Color(0xFF747775)
    val KakaoSurface = Color(0xFFFEE500)
    val KakaoText = Color.Black.copy(alpha = 0.85f)
    val NaverSurface = Color(0xFF03A94D)
    val NaverText = Color.White
    val Brand = Color(0xFF333039)          // 기존 워드마크 글자색
    val RecentSurface = Brand
    val RecentText = Color.White
}

/** 순수 로그인 UI. SDK·네트워크·세션·이동·타이머는 실행하지 않는다. */
@Composable
fun SocialLoginScreen(
    state: SocialLoginState,
    onLogin: (LoginProvider) -> Unit,
    modifier: Modifier = Modifier,
    showInlineError: Boolean = true,
) {
    BoxWithConstraints(
        modifier.fillMaxSize().background(LoginPalette.Background).safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val viewportHeight = maxHeight
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight).padding(AppSpacing.Section),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,     // 로고 · 문구 · 버튼을 한 덩어리로 화면 가운데에(길어지면 스크롤)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 로고는 이미지 대신 글자(Nunito Black 900, OFL — font/OFL-Nunito.txt)
                Text(BRAND, fontFamily = FontFamily(Font(Res.font.nunito_black, FontWeight.Black)), fontSize = 64.sp,
                    color = LoginPalette.Brand, modifier = Modifier.padding(top = AppSpacing.ExtraLarge, bottom = AppSpacing.Large))
                Text(stringResource(Res.string.login_heading),
                    style = MaterialTheme.typography.headlineMedium, color = AppColors.TextPrimary,
                    textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(AppSpacing.Medium))
                Text(stringResource(Res.string.login_intro), style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary, textAlign = TextAlign.Center)
            }
            Column(
                Modifier.padding(top = AppSpacing.Section),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LoginProvider.entries.filter { it in state.availableProviders }.forEach { provider ->
                    ProviderButton(provider, recent = provider == state.lastProvider, enabled = state.busyProvider == null && !state.completed,
                        onClick = { if (state.busyProvider == null && !state.completed) onLogin(provider) })
                }
                if (state.availableProviders.isEmpty()) {
                    Text(stringResource(Res.string.login_unavailable), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                }
                if (state.busyProvider != null) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        CircularProgressIndicator(Modifier.size(AppSpacing.ProgressSize),
                            color = AppColors.TextPrimary, strokeWidth = AppSpacing.FocusStroke)
                        Text(stringResource(state.busyProvider.busyLabel()),
                            style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                    }
                } else if (showInlineError && state.errorMessage != null) {
                    // Preview용. 실제 연결 화면은 SnackbarHost로 표시한다.
                    Snackbar(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(state.errorMessage)
                    }
                }
                Text(stringResource(Res.string.login_account_hint),
                    modifier = Modifier.padding(top = AppSpacing.Small),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary, textAlign = TextAlign.Center)
            }
        }
    }
}

// 마지막으로 쓴 버튼 오른쪽 위에 "최근 로그인" 말풍선
@Composable
private fun ProviderButton(provider: LoginProvider, recent: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val recentLabel = stringResource(Res.string.login_recent)
    Box(Modifier.fillMaxWidth()) {
        // 화면 읽기는 말풍선을 따로 읽지 않고 버튼 상태로 "카카오 로그인, 최근 로그인" 처럼 함께 읽는다
        ProviderButtonBody(provider, enabled, onClick,
            Modifier.semantics { if (recent) stateDescription = recentLabel })
        if (recent) Text(recentLabel,
            Modifier.align(Alignment.TopEnd).offset(x = (-12).dp, y = (-10).dp).clearAndSetSemantics {}
                .background(LoginPalette.RecentSurface, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
            color = LoginPalette.RecentText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ProviderButtonBody(provider: LoginProvider, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val background = when (provider) {
        LoginProvider.Google -> LoginPalette.GoogleSurface
        LoginProvider.Kakao -> LoginPalette.KakaoSurface
        LoginProvider.Naver -> LoginPalette.NaverSurface
    }
    val foreground = when (provider) {
        LoginProvider.Google -> LoginPalette.GoogleText
        LoginProvider.Kakao -> LoginPalette.KakaoText
        LoginProvider.Naver -> LoginPalette.NaverText
    }
    Button(
        onClick = { if (enabled) onClick() }, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(12.dp), elevation = null,
        border = if (provider == LoginProvider.Google) BorderStroke(1.dp, LoginPalette.GoogleBorder) else null,
        colors = ButtonDefaults.buttonColors(containerColor = background, contentColor = foreground,
            disabledContainerColor = background, disabledContentColor = foreground),
        contentPadding = PaddingValues(horizontal = AppSpacing.Large, vertical = AppSpacing.ExtraSmall),
    ) {
        // 공식 아이콘 원본은 변경하지 않는다. 이미지 안의 여백까지 고려한 표시 크기다.
        val iconSize = when (provider) {
            LoginProvider.Google -> 20.dp
            LoginProvider.Kakao -> 32.dp
            LoginProvider.Naver -> 48.dp
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(provider.icon()), contentDescription = null,
                modifier = Modifier.size(iconSize), contentScale = ContentScale.Fit)
        }
        Text(stringResource(provider.label()), modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center, fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium, fontSize = if (provider == LoginProvider.Google) 14.sp else 16.sp)
        Spacer(Modifier.width(AppSpacing.Small))
    }
}

private fun LoginProvider.icon(): DrawableResource = when (this) {
    LoginProvider.Google -> Res.drawable.login_google
    LoginProvider.Kakao -> Res.drawable.login_kakao
    LoginProvider.Naver -> Res.drawable.login_naver
}
private fun LoginProvider.label(): StringResource = when (this) {
    LoginProvider.Google -> Res.string.login_google
    LoginProvider.Kakao -> Res.string.login_kakao
    LoginProvider.Naver -> Res.string.login_naver
}
private fun LoginProvider.busyLabel(): StringResource = when (this) {
    LoginProvider.Google -> Res.string.login_google_busy
    LoginProvider.Kakao -> Res.string.login_kakao_busy
    LoginProvider.Naver -> Res.string.login_naver_busy
}
