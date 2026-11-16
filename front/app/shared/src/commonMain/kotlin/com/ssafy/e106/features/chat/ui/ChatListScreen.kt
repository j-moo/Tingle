package com.ssafy.e106.features.chat.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    Surface(modifier.fillMaxSize(), color = AppColors.Screen) {
        // 아래쪽 인셋은 틀(SignedInServiceHost)의 하단 바가 맡는다
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            AppTopBar(stringResource(Res.string.chat_list_title))
            val refresh = { if (state.load != ChatListLoad.Loading && !state.refreshing) onAction(ChatListAction.Refresh) }
            // 불러오기 실패: 동의가 필요하면 그 안내와 동의 화면으로 가는 버튼, 아니면 오류 종류에 맞는 상태 화면
            val failure: @Composable () -> Unit = {
                if (onConsent != null) {
                    StateView(StateKind.Locked, stringResource(Res.string.chat_access_consent))
                    AppButton(stringResource(Res.string.chat_access_open_consent), Modifier.fillMaxWidth(), onClick = onConsent)
                } else StateView(error.stateKind(), error.stateTitle(), onRetry = refresh, retryEnabled = !state.refreshing)
            }
            // 하단 바와 아래 띠는 목록 위에 떠 있다. 목록 끝에 그만큼 여백을 둬 마지막 항목이 그 위로 올라오게 한다
            val bar = LocalBottomBarPadding.current
            val density = LocalDensity.current
            var notices by remember { mutableStateOf(0.dp) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // 당기기를 못 하는 사용자(TalkBack · 스위치)를 위한 "새로고침" 접근성 동작은 PullDownRefreshBox 가 둔다
                PullDownRefreshBox(state.refreshing, refresh, Modifier.fillMaxSize()) {
                    // 내용이 짧아도 화면 전체에서 당길 수 있게 높이를 채운다
                    LazyColumn(state = list, modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                        contentPadding = screenPaddingValues(bar + notices), verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                        item(key = "intro") {
                            Text(stringResource(Res.string.chat_list_heading), style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.semantics { heading() })
                            // 7일 뒤 종료 · 다시 볼 수 없음 안내는 어떤 상태에서도 지우지 않는다. 연한 회색 안내 박스에 담는다
                            Text(stringResource(Res.string.chat_list_intro), Modifier.padding(top = AppSpacing.Medium).fillMaxWidth()
                                .glass(RoundedCornerShape(14.dp), AppColors.SurfaceMuted).padding(14.dp),
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
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = bar).onSizeChanged { notices = with(density) { it.height.toDp() } }) {
                    if (state.load == ChatListLoad.Ready && state.refreshFailed && rooms.isNotEmpty()) {
                        if (onConsent != null) NoticeBar(stringResource(Res.string.chat_access_consent),
                            actionText = stringResource(Res.string.chat_access_open_consent), onAction = onConsent)
                        else NoticeBar(stringResource(Res.string.chat_list_refresh_failed), actionEnabled = !state.refreshing, onAction = refresh)
                    }
                }
            }
        }
    }
}

private val UnreadGreen = Color(0xFF1B7F3A)   // 읽지 않은 메시지 배지 · 점. 흰 글자와 대비 약 5:1

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
        // 상대 프로필 사진. 없거나 받는 중이면 첫 글자(장식이라 읽지 않음)
        Box(Modifier.size(48.dp).clip(CircleShape).background(AppColors.SurfaceMuted), contentAlignment = Alignment.Center) {
            if (allowed && room.photo != null) Image(room.photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(if (allowed) room.nickname.initial() else "",
                style = MaterialTheme.typography.titleMedium, color = AppColors.TextSecondary, modifier = Modifier.clearAndSetSemantics {})
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (allowed) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            room.lastMessageTimeLabel?.takeIf { room.lastMessageKind == LastMessageKind.Text }?.let { listTimeLabel(it) }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, maxLines = 1)
            }
            // 읽지 않은 메시지 수를 초록 배지로(99 까지, 넘으면 99+). 수를 주지 않는 옛 서버는 초록 점만
            val badge = unreadBadge(room.unreadCount, room.hasNewMessages)
            if (!badge.isNullOrEmpty()) Text(badge,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1,
                modifier = Modifier.defaultMinSize(minWidth = 22.dp).background(UnreadGreen, CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp).semantics { contentDescription = "$newLabel $badge" },
                textAlign = TextAlign.Center)
            else if (badge != null) Box(Modifier.size(10.dp).clip(CircleShape).background(UnreadGreen)
                .semantics { contentDescription = newLabel })
        }
    }
}

/** 마지막 메시지 시각: 오늘이면 "오후 3:05", 아니면 "11월 15일". 서버 시각을 못 읽으면 표시하지 않는다 */
@Composable private fun listTimeLabel(iso: String): String? {
    val t = chatTimeParts(iso) ?: return null
    return if (isSameKstDay(iso, kotlin.time.Clock.System.now()))
        stringResource(if (t.afternoon) Res.string.chat_time_pm else Res.string.chat_time_am, t.hour12, t.minute.toString().padStart(2, '0'))
    else stringResource(Res.string.chat_date, t.month, t.day)
}

/** 한국 날짜(1970년부터 일 수)가 같은지. 1년 전 같은 날을 오늘로 보지 않는다. 못 읽으면 false */
internal fun isSameKstDay(iso: String, now: kotlin.time.Instant): Boolean {
    fun kstDay(instant: kotlin.time.Instant) = (instant.epochSeconds + 9 * 3600).floorDiv(86_400L)
    return runCatching { kstDay(kotlin.time.Instant.parse(iso)) == kstDay(now) }.getOrDefault(false)
}
