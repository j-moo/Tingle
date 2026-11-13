package com.ssafy.e106.features.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * 목록 표시나 콜백 자체로 새 메시지 확인 처리·방 입장을 확정하지 않는다. 새로고침은 맨 위에서 당겨서 한다.
 * [error] 는 불러오기 실패 화면의 종류(연결 · 서버)를 고르는 데만 쓴다. [onConsent] 를 주면 실패 대신 "동의 필요"와 동의 화면으로 가는 버튼을 보인다.
 */
@Composable
fun ChatListScreen(state: ChatListState, onOpenChat: (Long) -> Unit,
    onAction: (ChatListAction) -> Unit, modifier: Modifier = Modifier, error: AppError? = null, onConsent: (() -> Unit)? = null) {
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
            val refresh = { if (state.load != ChatListLoad.Loading && !state.refreshing) onAction(ChatListAction.Refresh) }
            // 불러오기 실패: 동의가 필요하면 그 안내와 동의 화면으로 가는 버튼, 아니면 오류 종류에 맞는 상태 화면
            val failure: @Composable () -> Unit = {
                if (onConsent != null) {
                    StateView(StateKind.Locked, stringResource(Res.string.chat_access_consent))
                    AppButton(stringResource(Res.string.chat_access_open_consent), Modifier.fillMaxWidth(), onClick = onConsent)
                } else StateView(error.stateKind(), error.stateTitle(), onRetry = refresh, retryEnabled = !state.refreshing)
            }
            // 당기기를 못 하는 사용자(TalkBack · 스위치)를 위한 "새로고침" 접근성 동작은 PullDownRefreshBox 가 둔다
            PullDownRefreshBox(state.refreshing, refresh, Modifier.weight(1f).fillMaxWidth()) {
                // 내용이 짧아도 화면 전체에서 당길 수 있게 높이를 채운다
                LazyColumn(state = list, modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                    contentPadding = screenPaddingValues(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    item(key = "intro") {
                        Text(stringResource(Res.string.chat_list_heading), style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { heading() })
                        // 7일 뒤 종료 · 다시 볼 수 없음 안내는 어떤 상태에서도 지우지 않는다
                        Text(stringResource(Res.string.chat_list_intro), Modifier.padding(top = AppSpacing.Medium),
                            style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                    }
                    when (state.load) {
                        ChatListLoad.Loading -> item(key = "loading") { SkeletonGroup { repeat(4) { SkeletonRow() } } }
                        ChatListLoad.Failed -> item(key = "failed") { failure() }
                        ChatListLoad.Ready -> {
                            when {
                                // 보여 줄 목록이 없는 채로 새로고침이 실패하면 띠 대신 실패 화면
                                rooms.isEmpty() && state.refreshFailed -> item(key = "empty") { failure() }
                                // 불러온 쪽이 비어도 다음 쪽이 남았으면 비었다고 단정하지 않는다(아래 이어 받기 줄이 대신한다)
                                rooms.isEmpty() && state.page == ChatListPage.End -> item(key = "empty") {
                                    StateView(StateKind.Empty, stringResource(Res.string.chat_list_empty), onRetry = refresh)
                                }
                            }
                            items(rooms, key = { "chat-${it.chatRoomId}" }) { room -> ChatCard(room, onOpenChat) }
                            // 목록 이어 받기: 받는 중 = 뼈대 줄, 실패 = 한 줄 + 다시 시도(자동 재시도는 안 함), 끝 = 작은 글
                            when {
                                state.page == ChatListPage.Failed -> item(key = "page") {
                                    ListTail(ListTailState.Failed, onRetry = { onAction(ChatListAction.LoadMore) }, retryEnabled = !state.refreshing)
                                }
                                state.page == ChatListPage.Loading || rooms.isEmpty() && !state.refreshFailed && state.page == ChatListPage.More ->
                                    item(key = "page") { ListTail(ListTailState.Loading) }
                                state.page == ChatListPage.End && rooms.isNotEmpty() -> item(key = "end") { ListTail(ListTailState.End) }
                            }
                        }
                    }
                }
            }
            // 보던 목록은 그대로 두고 새로고침 실패만 아래 띠로 알린다(동의가 필요해 실패했으면 동의 화면으로)
            if (state.load == ChatListLoad.Ready && state.refreshFailed && rooms.isNotEmpty()) {
                if (onConsent != null) NoticeBar(stringResource(Res.string.chat_access_consent),
                    actionText = stringResource(Res.string.chat_access_open_consent), onAction = onConsent)
                else NoticeBar(stringResource(Res.string.chat_list_refresh_failed), actionEnabled = !state.refreshing, onAction = refresh)
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
