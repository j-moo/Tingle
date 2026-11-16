package com.ssafy.e106.features.account.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
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
 * 위쪽에 닉네임, 아래 자주 쓰는 3개(프로필 보기 · 매칭 설정 · 내 키링)를 타일로, 나머지는 목록. 사진은 프로필 보기에서 보여 준다.
 * 내 정보를 받는 중 · 못 받았을 때도 메뉴(로그아웃 · 탈퇴 포함)는 그대로 쓸 수 있게 닉네임 자리만 뼈대 · 상태 표시로 바꾼다.
 */
@Composable
fun AccountMenuScreen(
    state: AccountMenuState,
    onAction: (AccountMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = AppColors.Screen) {
        // 아래쪽 인셋은 틀(SignedInServiceHost)의 하단 바가 맡는다
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            AppTopBar(stringResource(Res.string.account_title))
            // 당기면 다시 받는다. 다시 받는 동안은 사진 카드가 뼈대로 바뀌어 알리므로 고리는 따로 돌리지 않는다
            PullDownRefreshBox(false, { onAction(AccountMenuAction.Refresh) }, Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(screenPaddingValues(LocalBottomBarPadding.current)),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    when (state.load) {
                        AccountMenuLoad.Loading -> SkeletonGroup { SkeletonBone(Modifier.fillMaxWidth(0.5f), height = 32.dp) }
                        // 화면이 오류 종류를 받지 않아 "알 수 없는 오류" 모양으로 보인다
                        AccountMenuLoad.Failed -> StateView(StateKind.Unknown, stringResource(Res.string.account_failed),
                            onRetry = { onAction(AccountMenuAction.Refresh) })
                        AccountMenuLoad.Ready -> Text(state.nickname?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.account_name_unknown),
                            style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() })
                    }
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                        Tile(Res.string.account_profile_view, AccountMenuAction.Profile, onAction)
                        Tile(Res.string.account_matching, AccountMenuAction.Matching, onAction)
                        Tile(Res.string.account_keyring_tile, AccountMenuAction.Keyring, onAction)
                    }
                    Column {
                        // 타일에서 빠진 성향테스트는 목록 맨 위로
                        MenuRow(Res.string.account_survey, AccountMenuAction.Survey, onAction)
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
        }
    }
}

@Composable
private fun RowScope.Tile(title: StringResource, action: AccountMenuAction, onAction: (AccountMenuAction) -> Unit) {
    Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = 72.dp).glass(AppShapes.Card, AppColors.SurfaceMuted)
        .clickable(role = Role.Button) { onAction(action) }.padding(AppSpacing.Medium), contentAlignment = Alignment.Center) {
        Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = AppColors.TextPrimary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MenuRow(title: StringResource, action: AccountMenuAction, onAction: (AccountMenuAction) -> Unit, color: Color = AppColors.TextPrimary) {
    ServiceRow(stringResource(title), onClick = { onAction(action) }, titleColor = color)
}
