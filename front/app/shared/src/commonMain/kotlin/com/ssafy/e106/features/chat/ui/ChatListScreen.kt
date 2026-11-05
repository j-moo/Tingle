package com.ssafy.e106.features.chat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 목록 표시나 콜백 자체로 새 메시지 확인 처리·방 입장을 확정하지 않는다. */
@Composable
fun ChatListScreen(state: ChatListState, onOpenChat: (Long) -> Unit,
    onAction: (ChatListAction) -> Unit, modifier: Modifier = Modifier) {
    val rooms = visibleChatRooms(state.rooms)
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.chat_list_title))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    contentPadding = screenPaddingValues(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    item(key = "intro") {
                        Text(stringResource(Res.string.chat_list_heading), style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { heading() })
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
                            StatusCard(stringResource(Res.string.chat_list_failed), stringResource(Res.string.chat_list_failed_help))
                            AppButton(stringResource(Res.string.chat_list_retry), Modifier.fillMaxWidth().padding(top = AppSpacing.Large),
                                onClick = { onAction(ChatListAction.Refresh) })
                        }
                        ChatListLoad.Ready -> {
                            item(key = "refresh") {
                                AppButton(stringResource(Res.string.chat_list_refresh), Modifier.fillMaxWidth(),
                                    variant = AppButtonVariant.Secondary, loading = state.refreshing,
                                    loadingText = stringResource(Res.string.chat_list_refreshing),
                                    onClick = { onAction(ChatListAction.Refresh) })
                                if (state.refreshFailed) Text(stringResource(Res.string.chat_list_refresh_failed),
                                    Modifier.padding(top = AppSpacing.Medium).semantics { liveRegion = LiveRegionMode.Polite },
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
                            if (rooms.isNotEmpty() || state.page != ChatListPage.End) item(key = "page") {
                                if (state.page == ChatListPage.End) Text(stringResource(Res.string.chat_list_end),
                                    style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                                else AppButton(stringResource(if (state.page == ChatListPage.Failed) Res.string.chat_list_more_retry else Res.string.chat_list_more),
                                    Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                                    enabled = !state.refreshing, loading = state.page == ChatListPage.Loading,
                                    loadingText = stringResource(Res.string.chat_list_more_loading),
                                    onClick = { onAction(ChatListAction.LoadMore) })
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

@Composable
private fun ChatCard(room: ChatListItem, onOpenChat: (Long) -> Unit) {
    Surface(shape = AppShapes.Card, color = AppColors.SurfaceMuted) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.Large), verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            if (room.access != ChatRoomAccess.Allowed) {
                Text(stringResource(Res.string.chat_list_access_unknown), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.chat_list_access_help), style = MaterialTheme.typography.bodyMedium)
                AppButton(stringResource(Res.string.chat_list_open), Modifier.fillMaxWidth(), enabled = false, onClick = {})
            } else {
                Text(room.nickname?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.chat_list_name_unknown),
                    style = MaterialTheme.typography.titleMedium)
                if (room.hasNewMessages == true) Surface(shape = AppShapes.Small, color = AppColors.SelectionContainer) {
                    Text(stringResource(Res.string.chat_list_new), Modifier.padding(AppSpacing.Small),
                        style = MaterialTheme.typography.labelLarge, color = AppColors.OnSelection)
                } else if (room.hasNewMessages == null) Text(stringResource(Res.string.chat_list_new_unknown),
                    style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                val message = when (room.lastMessageKind) {
                    LastMessageKind.Text -> room.lastMessage?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.chat_list_message_unknown)
                    LastMessageKind.None -> stringResource(Res.string.chat_list_first_message)
                    LastMessageKind.Unknown -> stringResource(Res.string.chat_list_message_unknown)
                }
                Text(message, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (room.lastMessageKind == LastMessageKind.Text) Text(
                    room.lastMessageTimeLabel?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.chat_list_time_unknown),
                    style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                Text(room.closesAtLabel?.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.chat_list_closes, it) }
                    ?: stringResource(Res.string.chat_list_closes_unknown),
                    style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                AppButton(stringResource(Res.string.chat_list_open), Modifier.fillMaxWidth(),
                    onClick = { onOpenChat(room.chatRoomId) })
            }
        }
    }
}
