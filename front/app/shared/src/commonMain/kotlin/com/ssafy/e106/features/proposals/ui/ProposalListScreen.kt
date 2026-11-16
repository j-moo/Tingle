package com.ssafy.e106.features.proposals.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * 실제 조회·만료 판정·응답 제출·프로필 이동은 호출부의 책임이다. 새로고침은 맨 위에서 당겨서 한다.
 * [error] 는 불러오기 실패 화면의 종류(연결 · 서버 · 가입 단계)를 고르는 데만 쓴다. [notice] 는 아래 탭 바로 위에 붙는 알림 자리다.
 */
@Composable
fun ProposalListScreen(state: ProposalListState, onOpenProposal: (Long) -> Unit,
    onAction: (ProposalListAction) -> Unit, modifier: Modifier = Modifier, tab: Int = 0, onTab: (Int) -> Unit = {},
    error: AppError? = null, notice: @Composable () -> Unit = {}) {
    val entries = state.items.distinctBy { it.proposalId }.filter { it.photoState != ProposalPhotoState.Unavailable }
    // 남은 시간 표시용 시계. 1분마다 다시 그린다(마감 판정은 서버가 한다)
    // 시안 05: 위쪽 탭 "응답 대기 | 기다리는 중"(내가 수락하고 상대 응답을 기다리는 제안). 고른 탭은 연결부가 들고 있다
    val waiting = entries.filter { it.ended == null && it.myDecision == MyProposalDecision.AcceptedWaiting }
    // 끝난 제안은 응답 대기 맨 위
    val shown = if (tab == 0) entries.sortedBy { it.ended == null } - waiting.toSet() else waiting
    // 끝까지 내리면 다음 쪽을 자동으로 받는다(더 보기 버튼 없음). 화면에 다 들어와 스크롤이 없어도 끝이 보이면 받는다
    val list = rememberLazyListState()
    val atEnd by remember { derivedStateOf {
        val info = list.layoutInfo
        info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 2
    } }
    LaunchedEffect(atEnd, state.page, state.load, state.refreshing, state.refreshFailed) {
        if (atEnd && state.load == ProposalListLoad.Ready && state.page == ProposalPageState.More && !state.refreshing && !state.refreshFailed)
            onAction(ProposalListAction.LoadMore)
    }
    // 1분마다 남은 시간을 다시 그린다(값은 서버 기준 마감에서 받은 뒤 흐른 시간만 빼서 계산 — 기기 시계를 쓰지 않음)
    val tick by produceState(0) { while (true) { delay(60_000); value++ } }
    Surface(modifier.fillMaxSize(), color = AppColors.Screen) {
        // 아래쪽 인셋은 틀(SignedInServiceHost)의 하단 바가 맡는다
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            AppTopBar(stringResource(Res.string.proposal_list_title))
            val refresh = { if (state.load != ProposalListLoad.Loading && !state.refreshing) onAction(ProposalListAction.Refresh) }
            // 당기기를 못 하는 TalkBack 사용자를 위한 "새로고침" 접근성 동작은 PullDownRefreshBox 가 둔다
            PullDownRefreshBox(state.refreshing, refresh, Modifier.weight(1f).fillMaxWidth()) {
                // 내용이 짧아도 화면 전체에서 당길 수 있게 높이를 채운다
                LazyColumn(state = list, modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                    contentPadding = screenPaddingValues(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    item(key = "intro") {
                        Text(stringResource(Res.string.proposal_list_heading), style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { heading() })
                    }
                    when (state.load) {
                        // 뼈대: 탭 자리 + 제안 줄
                        ProposalListLoad.Loading -> item(key = "loading") {
                            SkeletonGroup {
                                SkeletonBone(height = 50.dp, shape = RoundedCornerShape(12.dp))
                                repeat(3) { SkeletonRow() }
                            }
                        }
                        ProposalListLoad.Failed -> item(key = "failure") {
                            StateView(error.stateKind(), error.stateTitle(), onRetry = { onAction(ProposalListAction.Refresh) })
                        }
                        ProposalListLoad.Ready -> {
                            // 받은 제안이 없어도 탭은 늘 보인다
                            item(key = "tabs") {
                                AppSegmentedTabs(listOf(stringResource(Res.string.proposal_list_tab_pending, entries.count { it.ended == null } - waiting.size),
                                    stringResource(Res.string.proposal_list_tab_waiting, waiting.size)), tab, onTab)
                            }
                            when {
                                // 보여 줄 목록이 없는 채로 새로고침이 실패하면 띠 대신 실패 화면
                                entries.isEmpty() && state.refreshFailed -> item(key = "empty") {
                                    StateView(error.stateKind(), error.stateTitle(), onRetry = refresh, retryEnabled = !state.refreshing)
                                }
                                // 현재 페이지가 만료/접근불가 항목으로 비어도 다음 커서가 있으면 조회는 끝나지 않았다(아래 이어 받기 줄이 대신한다)
                                entries.isEmpty() && state.page == ProposalPageState.End -> item(key = "empty") {
                                    StateView(StateKind.Empty, stringResource(Res.string.proposal_list_empty), onRetry = refresh)
                                }
                                entries.isNotEmpty() && shown.isEmpty() -> item(key = "tab-empty") {
                                    StateView(StateKind.Empty, stringResource(if (tab == 0) Res.string.proposal_list_pending_empty else Res.string.proposal_list_waiting_empty))
                                }
                            }
                            items(shown, key = { "proposal-${it.proposalId}" }) {
                                if (it.ended != null) EndedRow(it, onOpenProposal) else ProposalRow(it, tick, onOpenProposal)
                            }
                            // 목록 이어 받기: 받는 중 = 뼈대 줄, 실패 = 한 줄 + 다시 시도(자동 재시도는 안 함), 끝 = 작은 글
                            // 새로고침이 실패해 다음 쪽을 자동으로 받지 않는 동안엔 아래 띠의 "다시 시도"로 첫 쪽부터 다시 받는다
                            when {
                                state.page == ProposalPageState.Failed -> item(key = "paging") {
                                    ListTail(ListTailState.Failed, onRetry = { onAction(ProposalListAction.LoadMore) }, retryEnabled = !state.refreshing)
                                }
                                state.refreshFailed -> Unit
                                state.page == ProposalPageState.Loading || entries.isEmpty() && state.page == ProposalPageState.More ->
                                    item(key = "paging") { ListTail(ListTailState.Loading) }
                                state.page == ProposalPageState.End && shown.isNotEmpty() -> item(key = "end") { ListTail(ListTailState.End) }
                            }
                        }
                    }
                }
            }
            // 보던 목록은 그대로 두고 새로고침 실패만 아래 띠로 알린다
            if (state.load == ProposalListLoad.Ready && state.refreshFailed && entries.isNotEmpty())
                NoticeBar(stringResource(Res.string.proposal_list_refresh_failed), actionEnabled = !state.refreshing, onAction = refresh)
            notice()
        }
    }
}

/** 테두리 카드 한 줄: 이름 · 나이(기다리는 중이면 아래 "상대 응답을 기다리는 중"), 오른쪽 남은 시간 칩. 카드 전체를 누르면 프로필 */
@Composable
private fun ProposalRow(item: ProposalListItem, tick: Int, onOpenProposal: (Long) -> Unit) {
    val left = remember(item, tick) { item.remaining() }
    val urgent = left != null && left < ProposalUrgent
    Row(Modifier.fillMaxWidth().heightIn(min = AppSpacing.ControlMinHeight).clip(AppShapes.Card)
        .border(AppSpacing.Stroke, AppColors.BorderSubtle, AppShapes.Card)
        .clickable(role = Role.Button) { onOpenProposal(item.proposalId) }
        .padding(AppSpacing.Large),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        ProposalAvatar(item.photoState, item.photoRequest?.takeIf { it.proposalId == item.proposalId }, item.photoImage, item.nickname, 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
            Text(item.nickname?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.proposal_list_name_unknown),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (item.age != null && item.age >= 19) stringResource(Res.string.proposal_list_age, item.age)
                else stringResource(Res.string.proposal_list_age_unknown),
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            if (item.myDecision == MyProposalDecision.AcceptedWaiting) Text(stringResource(Res.string.proposal_list_waiting),
                style = MaterialTheme.typography.bodySmall, color = AppColors.OnSelection)
        }
        if (left != null) Surface(shape = CircleShape, color = if (urgent) AppColors.Error.copy(alpha = 0.1f) else AppColors.SurfaceMuted) {
            Text(when (val label = remainingLabel(left)) {
                is RemainingLabel.Hours -> stringResource(Res.string.proposal_list_hours_left, label.hours)
                is RemainingLabel.Minutes -> stringResource(Res.string.proposal_list_minutes_left, label.minutes)
                RemainingLabel.Closing -> stringResource(Res.string.proposal_list_closing)
            }, Modifier.padding(horizontal = AppSpacing.Small, vertical = AppSpacing.ExtraSmall),
                style = MaterialTheme.typography.labelMedium, color = if (urgent) AppColors.Error else AppColors.TextSecondary)
        }
        // 장식이라 화면 리더에 읽히지 않게 한다
        Text("›", style = MaterialTheme.typography.labelLarge, color = AppColors.TextSecondary, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** 끝난 제안 한 줄: "매칭이 안 된 제안이 있어요" / "기한이 지난 제안이 있어요"(상대 이름은 남기지 않음). 누르면 결과 화면 → 확인하면 사라짐 */
@Composable
private fun EndedRow(item: ProposalListItem, onOpenProposal: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = AppSpacing.ControlMinHeight).glass(AppShapes.Card, AppColors.SurfaceMuted)
        .clickable(role = Role.Button) { onOpenProposal(item.proposalId) }
        .padding(AppSpacing.Large),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        Text(stringResource(if (item.ended == ProposalDecisionStage.Expired) Res.string.proposal_list_ended_expired_anon
            else Res.string.proposal_list_ended_not_matched_anon),
            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
        Text(stringResource(Res.string.proposal_list_ended_check), style = MaterialTheme.typography.labelMedium, color = AppColors.TextPrimary)
        Text("›", style = MaterialTheme.typography.labelLarge, color = AppColors.TextSecondary, modifier = Modifier.clearAndSetSemantics {})
    }
}
