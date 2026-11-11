package com.ssafy.e106.features.account.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * 순수 메뉴 화면. 데이터 조회·화면 이동·계정 변경은 호출부가 담당한다.
 * 위쪽에 내 프로필 사진을 큰 네모로 통째로(잘리지 않게) 보여 주고 그 위에 닉네임, 아래 자주 쓰는 3개를 타일로, 나머지는 목록.
 */
@Composable
fun AccountMenuScreen(
    state: AccountMenuState,
    onAction: (AccountMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.account_title))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    when (state.load) {
                        AccountMenuLoad.Loading -> Box(Modifier.align(Alignment.CenterHorizontally).widthIn(max = HeroMax).fillMaxWidth().aspectRatio(1f).clip(AppShapes.Card).background(AppColors.SurfaceMuted),
                            contentAlignment = Alignment.Center) {
                            val loading = stringResource(Res.string.account_loading)
                            CircularProgressIndicator(Modifier.semantics { contentDescription = loading }, color = AppColors.SelectionBorder)
                        }
                        AccountMenuLoad.Failed -> {
                            StatusCard(stringResource(Res.string.account_failed), stringResource(Res.string.account_failed_help))
                            AppButton(stringResource(Res.string.account_retry), Modifier.fillMaxWidth(),
                                onClick = { onAction(AccountMenuAction.Refresh) })
                        }
                        AccountMenuLoad.Ready -> ProfileHero(state, Modifier.align(Alignment.CenterHorizontally)) { onAction(AccountMenuAction.Profile) }
                    }
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                        Tile(Res.string.account_survey, AccountMenuAction.Survey, onAction)
                        Tile(Res.string.account_matching, AccountMenuAction.Matching, onAction)
                        Tile(Res.string.account_keyring_tile, AccountMenuAction.Keyring, onAction)
                    }
                    Column {
                        MenuRow(Res.string.account_permissions, AccountMenuAction.Permissions, onAction)
                        MenuRow(Res.string.account_consents, AccountMenuAction.Consents, onAction)
                        MenuRow(Res.string.account_section_help, AccountMenuAction.Help, onAction)
                        MenuRow(Res.string.account_logout, AccountMenuAction.Logout, onAction)
                        MenuRow(Res.string.account_withdrawal, AccountMenuAction.Withdrawal, onAction, AppColors.Error)
                    }
                    state.appVersion?.takeIf { it.isNotBlank() }?.let {
                        Text(stringResource(Res.string.account_version, it), style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextSecondary)
                    }
                }
            }
            AppBottomBar(labels = listOf(stringResource(Res.string.home_tab), stringResource(Res.string.home_proposal_tab),
                stringResource(Res.string.home_chat_tab), stringResource(Res.string.home_profile_tab)), selected = 3,
                onSelect = { index -> when (index) {
                    0 -> onAction(AccountMenuAction.Home)
                    1 -> onAction(AccountMenuAction.Proposals)
                    2 -> onAction(AccountMenuAction.Chats)
                } })
        }
    }
}

/** 가로 화면 · 태블릿에서 네모가 화면보다 커지지 않게 하는 상한. */
private val HeroMax = 360.dp

/** 큰 네모 사진(통째로 보이게 Fit, 남는 곳은 회색). 사진이 없거나 받는 중이면 기본 사람 모양. 아래쪽에 닉네임과 프로필로 가는 버튼. */
@Composable
private fun ProfileHero(state: AccountMenuState, modifier: Modifier = Modifier, onProfile: () -> Unit) {
    val name = state.nickname?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.account_name_unknown)
    Box(modifier.widthIn(max = HeroMax).fillMaxWidth().aspectRatio(1f).clip(AppShapes.Card).background(AppColors.SurfaceMuted), contentAlignment = Alignment.Center) {
        if (state.photo != null) Image(state.photo, stringResource(Res.string.account_photo_description), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        // 기본 사진: 회색 바탕에 사람 모양(머리 원 + 어깨). 장식이라 화면 리더는 읽지 않는다
        else Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            drawCircle(Silhouette, radius = w * 0.17f, center = Offset(w / 2, w * 0.38f))
            drawOval(Silhouette, topLeft = Offset(w * 0.16f, w * 0.62f), size = Size(w * 0.68f, w * 0.8f))
        }
        // 밝은 사진 · 회색 바탕에서도 흰 닉네임이 읽히게 글자 뒤는 충분히 어둡게(위 30% 구간에서 어두워진다)
        // 닉네임 아래에 버튼을 둬서 큰 글씨에서도 닉네임이 버튼에 밀려 잘리지 않게 한다
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.75f)))
            .padding(start = AppSpacing.Large, end = AppSpacing.Large, bottom = AppSpacing.Large, top = AppSpacing.Section + AppSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            Text(name, style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() })
            Box(Modifier.heightIn(min = AppSpacing.ControlMinHeight).clip(CircleShape).background(Color.White)
                .clickable(role = Role.Button, onClick = onProfile).padding(horizontal = AppSpacing.Large), contentAlignment = Alignment.Center) {
                Text(stringResource(if (state.hasPhoto) Res.string.account_profile_edit else Res.string.account_photo_add),
                    style = MaterialTheme.typography.labelLarge, color = AppColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun RowScope.Tile(title: StringResource, action: AccountMenuAction, onAction: (AccountMenuAction) -> Unit) {
    Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = 72.dp).clip(AppShapes.Card).background(AppColors.SurfaceMuted)
        .clickable(role = Role.Button) { onAction(action) }.padding(AppSpacing.Medium), contentAlignment = Alignment.Center) {
        Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = AppColors.TextPrimary, textAlign = TextAlign.Center)
    }
}

/** 기본 사진의 사람 모양 색(회색 바탕 위에서 보이는 정도의 회색). */
private val Silhouette = Color(0xFFD4D4D4)

@Composable
private fun MenuRow(title: StringResource, action: AccountMenuAction, onAction: (AccountMenuAction) -> Unit, color: Color = AppColors.TextPrimary) {
    ServiceRow(stringResource(title), onClick = { onAction(action) }, titleColor = color)
}
