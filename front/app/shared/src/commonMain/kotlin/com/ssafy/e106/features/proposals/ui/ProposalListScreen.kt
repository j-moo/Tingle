package com.ssafy.e106.features.proposals.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 실제 조회·만료 판정·응답 제출·프로필 이동은 호출부의 책임이다. */
@Composable
fun ProposalListScreen(state: ProposalListState, onOpenProposal: (Long) -> Unit,
    onAction: (ProposalListAction) -> Unit, modifier: Modifier = Modifier) {
    val entries = state.items.distinctBy { it.proposalId }.filter { it.photoState != ProposalPhotoState.Unavailable }
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.proposal_list_title))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    contentPadding = screenPaddingValues(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    item(key = "intro") {
                        Text(stringResource(Res.string.proposal_list_heading), style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { heading() })
                        Text(stringResource(Res.string.proposal_list_intro), Modifier.padding(top = AppSpacing.Medium),
                            style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
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
                            item(key = "refresh") {
                                AppButton(stringResource(Res.string.proposal_list_refresh), Modifier.fillMaxWidth(),
                                    variant = AppButtonVariant.Secondary, loading = state.refreshing,
                                    loadingText = stringResource(Res.string.proposal_list_refreshing),
                                    onClick = { onAction(ProposalListAction.Refresh) })
                                if (state.refreshFailed) Text(stringResource(Res.string.proposal_list_refresh_failed),
                                    Modifier.padding(top = AppSpacing.Medium).semantics { liveRegion = LiveRegionMode.Polite },
                                    color = AppColors.Error, style = MaterialTheme.typography.bodyMedium)
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
                            items(entries, key = { "proposal-${it.proposalId}" }) { proposal ->
                                ProposalCard(proposal, onOpenProposal)
                            }
                            // 표시할 항목 수가 아니라 서버 커서 상태로 더 보기/실패 재시도를 유지한다.
                            if (entries.isNotEmpty() || state.page != ProposalPageState.End) item(key = "paging") {
                                if (state.page == ProposalPageState.End) Text(stringResource(Res.string.proposal_list_end),
                                    style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                                else AppButton(stringResource(if (state.page == ProposalPageState.Failed) Res.string.proposal_list_more_retry else Res.string.proposal_list_more),
                                    Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                                    enabled = !state.refreshing, loading = state.page == ProposalPageState.Loading,
                                    loadingText = stringResource(Res.string.proposal_list_more_loading),
                                    onClick = { onAction(ProposalListAction.LoadMore) })
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

@Composable
private fun ProposalCard(item: ProposalListItem, onOpenProposal: (Long) -> Unit) {
    Surface(shape = AppShapes.Card, color = AppColors.SurfaceMuted) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.Large), verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                ProposalPhoto(item.photoState, item.photoRequest?.takeIf { it.proposalId == item.proposalId }, item.photoImage,
                    Modifier.width(AppSizes.Avatar).heightIn(min = AppSizes.Avatar), compact = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
                    Text(item.nickname?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.proposal_list_name_unknown),
                        style = MaterialTheme.typography.titleMedium)
                    Text(if (item.age != null && item.age >= 19) stringResource(Res.string.proposal_list_age, item.age)
                        else stringResource(Res.string.proposal_list_age_unknown),
                        style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                }
            }
            Text(stringResource(if (item.myDecision == MyProposalDecision.NotResponded) Res.string.proposal_list_not_responded else Res.string.proposal_list_accepted),
                style = MaterialTheme.typography.labelLarge, color = AppColors.OnSelection)
            Text(item.deadlineLabel?.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.proposal_list_deadline, it) }
                ?: stringResource(Res.string.proposal_list_deadline_unknown),
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            if (item.myDecision == MyProposalDecision.AcceptedWaiting) Text(stringResource(Res.string.proposal_list_waiting_help),
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            AppButton(stringResource(Res.string.proposal_list_open_profile), Modifier.fillMaxWidth(),
                onClick = { onOpenProposal(item.proposalId) })
        }
    }
}
