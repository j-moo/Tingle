package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSizes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.screenPaddingValues
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 상태 화면의 종류. 아이콘(Lucide)만 다르고 모양은 같다.
 * 불러오기 실패(Offline · Server · Unknown · Session) / 비어 있음(Empty) / 이용할 수 없음(Locked) /
 * 결과 확인 필요(Unconfirmed) / 완료(Done) / 내용이 바뀜(Changed)
 */
enum class StateKind(internal val icon: DrawableResource) {
    Offline(Res.drawable.ic_state_wifi_off), Server(Res.drawable.ic_state_cloud_off), Unknown(Res.drawable.ic_state_circle_alert),
    Session(Res.drawable.ic_state_log_in), Empty(Res.drawable.ic_state_inbox), Locked(Res.drawable.ic_state_lock),
    Unconfirmed(Res.drawable.ic_state_circle_help), Done(Res.drawable.ic_state_circle_check), Changed(Res.drawable.ic_state_refresh_cw),
}

/** 오류 → 상태 화면 종류. 오류가 없는데 실패한 경우(null)는 알 수 없는 오류로 본다 */
fun AppError?.stateKind(): StateKind = when (this) {
    AppError.Offline -> StateKind.Offline
    is AppError.Server -> StateKind.Server
    AppError.SessionExpired -> StateKind.Session
    is AppError.OnboardingRequired -> StateKind.Locked
    else -> StateKind.Unknown
}

/** 불러오기 실패의 짧은 제목 한 줄(설명은 쓰지 않는다). 화면이 자기 문구를 쓰려면 이 함수를 쓰지 않으면 된다 */
@Composable
fun AppError?.stateTitle(): String = stringResource(when (stateKind()) {
    StateKind.Offline -> Res.string.state_offline
    StateKind.Server -> Res.string.state_server
    StateKind.Session -> Res.string.state_session
    StateKind.Locked -> Res.string.state_locked
    else -> Res.string.state_unknown
})

/** 상태 화면 아래 큰 버튼 하나 */
data class StateAction(val label: String, val loading: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * 상태 화면의 가운데 내용: 동그라미 안 아이콘 + 짧은 제목 + (작은 글자 버튼).
 * 목록 안이나 아래 탭이 있는 화면처럼 틀이 따로 있는 곳에 넣어 쓴다. [onRetry] 는 아래 큰 버튼이 없을 때만 준다.
 * [note] 는 꾸밈 설명이 아니라 사용자가 꼭 알아야 하는 사실(예: "다시 보내지 않고 결과부터 확인해요")에만 쓴다.
 */
@Composable
fun StateView(kind: StateKind, title: String, modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null, retryText: String = stringResource(Res.string.state_retry), retryEnabled: Boolean = true,
    note: String? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = AppSpacing.Section), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
        val done = kind == StateKind.Done
        Box(Modifier.size(StateBadge).background(if (done) AppColors.SelectionContainer else AppColors.SurfaceMuted, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(kind.icon), null, Modifier.size(StateIcon), tint = if (done) AppColors.OnSelection else AppColors.TextPrimary)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
        if (note != null) Text(note, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
        if (onRetry != null) TextAction(retryText, onRetry, enabled = retryEnabled)
    }
}

/** 작은 글자 버튼(새로고침 아이콘 + 글자). 당길 수 없는 사용자도 다시 시도할 수 있게 큰 버튼이 없는 상태 화면에 둔다 */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Boolean = true) {
    val color = if (enabled) AppColors.TextPrimary else AppColors.TextDisabled
    Row(modifier.clip(AppShapes.Small).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = AppSizes.TouchTarget).padding(horizontal = AppSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        if (icon) Icon(painterResource(Res.drawable.ic_state_refresh_cw), null, Modifier.size(18.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/**
 * 화면 전체를 차지하는 상태 화면: 위쪽 줄(선택) + 가운데 [StateView] + 아래 큰 버튼(선택, 최대 2개).
 * [onRefresh] 가 있으면 당겨서 새로고침이 되고, 큰 버튼이 하나도 없으면 제목 아래 작은 글자 버튼 "다시 시도"가 나온다.
 */
@Composable
fun StateScreen(kind: StateKind, title: String, modifier: Modifier = Modifier, barTitle: String? = null, onBack: (() -> Unit)? = null,
    refreshing: Boolean = false, onRefresh: (() -> Unit)? = null, primary: StateAction? = null, secondary: StateAction? = null,
    note: String? = null) {
    Surface(modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (barTitle != null) AppTopBar(barTitle, onBack = onBack)
            val center: @Composable BoxScope.() -> Unit = {
                // 빈 곳을 당겨도 새로고침이 되게 화면 높이를 다 쓰고 스크롤할 수 있게 둔다
                Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.Center) {
                    StateView(kind, title, onRetry = onRefresh.takeIf { primary == null && secondary == null }, retryEnabled = !refreshing, note = note)
                }
            }
            if (onRefresh != null) PullDownRefreshBox(refreshing, onRefresh, Modifier.weight(1f).fillMaxWidth(), content = center)
            else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter, content = center)
            if (primary != null || secondary != null) Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                .padding(horizontal = AppSpacing.Large).padding(top = AppSpacing.Small, bottom = AppSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                primary?.let { AppButton(it.label, Modifier.fillMaxWidth(), loading = it.loading, enabled = it.enabled, onClick = it.onClick) }
                secondary?.let { AppButton(it.label, Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                    loading = it.loading, enabled = it.enabled, onClick = it.onClick) }
            }
        }
    }
}

private val StateBadge = 88.dp
private val StateIcon = 40.dp
