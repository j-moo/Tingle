package com.ssafy.e106.features.proposals.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** 실제 조회·만료 판정·응답 제출·프로필 이동은 호출부의 책임이다. 새로고침은 맨 위에서 당겨서 한다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposalListScreen(state: ProposalListState, onOpenProposal: (Long) -> Unit,
    onAction: (ProposalListAction) -> Unit, modifier: Modifier = Modifier, tab: Int = 0, onTab: (Int) -> Unit = {}) {
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
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.proposal_list_title))
            val refreshLabel = stringResource(Res.string.proposal_list_refresh)
            val refresh = { if (state.load != ProposalListLoad.Loading && !state.refreshing) onAction(ProposalListAction.Refresh) }
            PullToRefreshBox(state.refreshing, refresh, Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                // 당기기를 못 하는 TalkBack 사용자도 새로고침할 수 있게 같은 동작을 접근성 동작으로 둔다
                LazyColumn(state = list, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .semantics { customActions = listOf(CustomAccessibilityAction(refreshLabel) { refresh(); true }) },
                    contentPadding = screenPaddingValues(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    item(key = "intro") {
                        Text(stringResource(Res.string.proposal_list_heading), style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { heading() })
                    }
                    when (state.load) {
                        ProposalListLoad.Loading -> item(key = "loading") {
                            StatusCard(stringResource(Res.string.proposal_list_loading), stringResource(Res.string.proposal_list_loading_help))
                            Box(Modifier.fillMaxWidth().padding(AppSpacing.Section), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = AppColors.SelectionBorder)
                            }
                        }
                        ProposalListLoad.Failed -> item(key = "failure") {
                            StatusCard(stringResource(Res.string.proposal_list_failed), stringResource(Res.string.proposal_list_failed_help))
                            AppButton(stringResource(Res.string.proposal_list_retry), Modifier.fillMaxWidth().padding(top = AppSpacing.Large),
                                onClick = { onAction(ProposalListAction.Refresh) })
                        }
                        ProposalListLoad.Ready -> {
                            if (state.refreshFailed) item(key = "refresh-failed") {
                                Text(stringResource(Res.string.proposal_list_refresh_failed),
                                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    color = AppColors.Error, style = MaterialTheme.typography.bodyMedium)
                            }
                            // 받은 제안이 없어도 탭은 늘 보인다
                            item(key = "tabs") {
                                AppSegmentedTabs(listOf(stringResource(Res.string.proposal_list_tab_pending, entries.count { it.ended == null } - waiting.size),
                                    stringResource(Res.string.proposal_list_tab_waiting, waiting.size)), tab, onTab)
                            }
                            if (entries.isEmpty()) item(key = "empty") {
                                // 현재 페이지가 만료/접근불가 항목으로 비어도 다음 커서가 있으면 조회는 끝나지 않았다.
                                val incomplete = state.page != ProposalPageState.End
                                StatusCard(stringResource(when {
                                    state.refreshFailed -> Res.string.proposal_list_no_verified_title
                                    incomplete -> Res.string.proposal_list_partial_empty
                                    else -> Res.string.proposal_list_empty
                                }), stringResource(when {
                                    state.refreshFailed -> Res.string.proposal_list_no_verified_help
                                    incomplete -> Res.string.proposal_list_partial_empty_help
                                    else -> Res.string.proposal_list_empty_help
                                }))
                            }
                            if (entries.isNotEmpty()) {
                                if (shown.isEmpty()) item(key = "tab-empty") {
                                    Text(stringResource(if (tab == 0) Res.string.proposal_list_pending_empty else Res.string.proposal_list_waiting_empty),
                                        Modifier.fillMaxWidth().padding(vertical = AppSpacing.Section),
                                        style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
                                }
                                items(shown, key = { "proposal-${it.proposalId}" }) {
                                    if (it.ended != null) EndedRow(it, onOpenProposal) else ProposalRow(it, tick, onOpenProposal)
                                }
                            }
                            // 실제로 받는 중일 때만 돌아가는 표시, 실패면 다시 시도(자동 재시도는 안 함). 그 밖엔 아무것도 없음
                            // 새로고침이 실패해 다음 쪽을 자동으로 받지 않는 동안엔 "다시 불러오기"(첫 쪽부터)
                            val refreshToContinue = state.refreshFailed && state.page == ProposalPageState.More
                            if (state.page == ProposalPageState.Loading || state.page == ProposalPageState.Failed || refreshToContinue) item(key = "paging") {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    if (refreshToContinue) AppButton(stringResource(Res.string.proposal_list_retry), Modifier.fillMaxWidth(),
                                        variant = AppButtonVariant.Secondary, enabled = !state.refreshing, onClick = refresh)
                                    else when (state.page) {
                                        ProposalPageState.Failed -> AppButton(stringResource(Res.string.proposal_list_more_retry), Modifier.fillMaxWidth(),
                                            variant = AppButtonVariant.Secondary, enabled = !state.refreshing,
                                            onClick = { onAction(ProposalListAction.LoadMore) })
                                        else -> CircularProgressIndicator(Modifier.size(24.dp), color = AppColors.SelectionBorder, strokeWidth = 2.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            AppBottomBar(labels = listOf(stringResource(Res.string.proposal_list_home), stringResource(Res.string.proposal_list_tab),
                stringResource(Res.string.proposal_list_chats), stringResource(Res.string.proposal_list_profile)), selected = 1,
                onSelect = { when (it) {
                    0 -> onAction(ProposalListAction.Home)
                    2 -> onAction(ProposalListAction.Chats)
                    3 -> onAction(ProposalListAction.Profile)
                } })
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
    Row(Modifier.fillMaxWidth().heightIn(min = AppSpacing.ControlMinHeight).clip(AppShapes.Card)
        .background(AppColors.SurfaceMuted)
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
