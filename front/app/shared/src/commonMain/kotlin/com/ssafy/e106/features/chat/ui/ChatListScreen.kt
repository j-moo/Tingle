package com.ssafy.e106.features.chat.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 목록 표시나 콜백 자체로 새 메시지 확인 처리·방 입장을 확정하지 않는다. 새로고침은 맨 위에서 당겨서 한다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(state: ChatListState, onOpenChat: (Long) -> Unit,
    onAction: (ChatListAction) -> Unit, modifier: Modifier = Modifier) {
    val rooms = visibleChatRooms(state.rooms)
    // 끝까지 내리면 다음 쪽을 자동으로 받는다(더 보기 버튼 없음). 새로고침 중엔 받지 않는다
    val list = rememberLazyListState()
    val atEnd by remember { derivedStateOf {
        val info = list.layoutInfo
        info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 2
    } }
    LaunchedEffect(atEnd, state.page, state.load, state.refreshing) {
        if (atEnd && state.load == ChatListLoad.Ready && state.page == ChatListPage.More && !state.refreshing)
            onAction(ChatListAction.LoadMore)
    }
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.chat_list_title))
            val refreshLabel = stringResource(Res.string.chat_list_refresh)
            val refresh = { if (state.load != ChatListLoad.Loading && !state.refreshing) onAction(ChatListAction.Refresh) }
            PullToRefreshBox(state.refreshing, refresh, Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                // 내용이 짧아도 화면 전체에서 당길 수 있게 높이를 채운다
                LazyColumn(state = list, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(),
                    contentPadding = screenPaddingValues(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    item(key = "intro") {
                        // 당기기를 못 하는 사용자(TalkBack · 스위치)는 이 제목의 "새로고침" 동작으로 다시 받는다
                        Text(stringResource(Res.string.chat_list_heading), style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { heading(); customActions = listOf(CustomAccessibilityAction(refreshLabel) { refresh(); true }) })
                        Text(stringResource(Res.string.chat_list_intro), Modifier.padding(top = AppSpacing.Medium),
                            style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                    }
                    when (state.load) {
                        ChatListLoad.Loading -> item(key = "loading") {
                            StatusCard(stringResource(Res.string.chat_list_loading), stringResource(Res.string.chat_list_loading_help))
                            Box(Modifier.fillMaxWidth().padding(AppSpacing.Section), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = AppColors.SelectionBorder)
                            }
                        }
                        ChatListLoad.Failed -> item(key = "failed") {
                            // 다시 불러오기 버튼 없이 당겨서 새로고침으로 다시 받는다
                            StatusCard(stringResource(Res.string.chat_list_failed), stringResource(Res.string.chat_list_failed_help))
                        }
                        ChatListLoad.Ready -> {
                            if (state.refreshFailed) item(key = "refresh-failed") {
                                Text(stringResource(Res.string.chat_list_refresh_failed),
                                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    color = AppColors.Error, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (rooms.isEmpty()) item(key = "empty") {
                                val incomplete = state.page != ChatListPage.End
                                StatusCard(stringResource(when {
                                    state.refreshFailed -> Res.string.chat_list_no_verified
                                    incomplete -> Res.string.chat_list_partial_empty
                                    else -> Res.string.chat_list_empty
                                }), stringResource(when {
                                    state.refreshFailed -> Res.string.chat_list_no_verified_help
                                    incomplete -> Res.string.chat_list_partial_empty_help
                                    else -> Res.string.chat_list_empty_help
                                }))
                            }
                            items(rooms, key = { "chat-${it.chatRoomId}" }) { room -> ChatCard(room, onOpenChat) }
                            // 받는 중일 때만 돌아가는 표시, 다음 쪽 실패면 다시 시도(자동 재시도는 안 함). 새로고침 실패 뒤엔 당겨서 다시 받는다
                            if (state.page == ChatListPage.Loading || state.page == ChatListPage.Failed) item(key = "page") {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    if (state.page == ChatListPage.Failed) AppButton(stringResource(Res.string.chat_list_more_retry), Modifier.fillMaxWidth(),
                                        variant = AppButtonVariant.Secondary, enabled = !state.refreshing,
                                        onClick = { onAction(ChatListAction.LoadMore) })
                                    else CircularProgressIndicator(Modifier.size(24.dp), color = AppColors.SelectionBorder, strokeWidth = 2.dp)
                                }
                            }
                        }
                    }
                }
            }
            AppBottomBar(labels = listOf(stringResource(Res.string.chat_list_home), stringResource(Res.string.chat_list_proposals),
                stringResource(Res.string.chat_list_title), stringResource(Res.string.chat_list_profile)), selected = 2,
                onSelect = { when (it) {
                    0 -> onAction(ChatListAction.Home)
                    1 -> onAction(ChatListAction.Proposals)
                    3 -> onAction(ChatListAction.Profile)
                } })
        }
    }
}

/**
 * 채팅 앱처럼 한 줄: 왼쪽 동그란 첫 글자 · 가운데 이름과 마지막 메시지 한 줄 · 오른쪽 시각과 새 메시지 점.
 * 줄 전체를 누르면 대화. 이용 여부를 아직 모르는 방은 흐리게, 누를 수 없게.
 */
@Composable
private fun ChatCard(room: ChatListItem, onOpenChat: (Long) -> Unit) {
    val allowed = room.access == ChatRoomAccess.Allowed
    val name = if (!allowed) stringResource(Res.string.chat_list_access_unknown)
        else room.nickname?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.chat_list_name_unknown)
    val message = when {
        !allowed -> stringResource(Res.string.chat_list_access_help)
        room.lastMessageKind == LastMessageKind.Text -> room.lastMessage?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.chat_list_message_unknown)
        room.lastMessageKind == LastMessageKind.None -> stringResource(Res.string.chat_list_first_message)
        else -> stringResource(Res.string.chat_list_message_unknown)
    }
    val newLabel = stringResource(Res.string.chat_list_new)
    Row(Modifier.fillMaxWidth().clip(AppShapes.Card)
        .then(if (allowed) Modifier.clickable(role = Role.Button) { onOpenChat(room.chatRoomId) } else Modifier.alpha(0.5f))
        .padding(vertical = AppSpacing.Small),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        // 목록엔 사진이 없어 첫 글자(장식이라 읽지 않음)
        Box(Modifier.size(48.dp).clip(CircleShape).background(AppColors.SurfaceMuted), contentAlignment = Alignment.Center) {
            Text(if (allowed) room.nickname.initial() else "",
                style = MaterialTheme.typography.titleMedium, color = AppColors.TextSecondary, modifier = Modifier.clearAndSetSemantics {})
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (allowed) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            room.lastMessageTimeLabel?.takeIf { it.isNotBlank() && room.lastMessageKind == LastMessageKind.Text }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, maxLines = 1)
            }
            // 새 메시지가 있으면 초록 점(화면 리더엔 "새 메시지"). 모르면 표시하지 않는다
            if (room.hasNewMessages == true) Box(Modifier.size(10.dp).clip(CircleShape).background(AppColors.SelectionBorder)
                .semantics { contentDescription = newLabel })
        }
    }
}
