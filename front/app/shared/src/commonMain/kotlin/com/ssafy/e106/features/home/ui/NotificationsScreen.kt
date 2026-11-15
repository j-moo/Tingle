package com.ssafy.e106.features.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.push.InboxItem
import com.ssafy.e106.core.push.NotificationInbox
import com.ssafy.e106.core.push.PushTarget
import com.ssafy.e106.core.ui.components.AppTopBar
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.features.chat.ui.chatTimeParts
import com.ssafy.e106.features.chat.ui.isSameKstDay
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.Instant

/** 홈 종 모양에서 여는 받은 알림 모음. 열면 모두 읽음으로 바꾸되, 이번에 처음 본 알림은 점으로 표시해 둔다 */
@Composable internal fun NotificationsRoute(session: Session.SignedIn, onBack: () -> Unit, onOpen: (PushTarget) -> Unit,
    inbox: NotificationInbox = koinInject()) {
    val items by inbox.items.collectAsState(emptyList())
    // 이 화면에서 처음 본 알림(열 때 안 읽음이었거나 보는 중에 새로 온 것). 읽음으로 바꾸기 전에 모아 둔다
    var fresh by remember(session.userId) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(session.userId, items) {
        val unread = items.filterNot { it.read }
        if (unread.isNotEmpty()) { fresh = fresh + unread.map { it.id }; inbox.markAllRead(session.userId) }
    }
    ProfileBackHandler(true, onBack)
    NotificationsScreen(items, fresh, onBack, onOpen)
}

@Composable internal fun NotificationsScreen(items: List<InboxItem>, fresh: Set<String>, onBack: () -> Unit, onOpen: (PushTarget) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.notifications_title), onBack = onBack)
            if (items.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().padding(screenPaddingValues()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.notifications_empty), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(Res.string.notifications_help), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
            else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = screenPaddingValues()) {
                items(items, key = { it.id }) { item -> InboxRow(item, item.id in fresh) { onOpen(item.target) } }
                item {
                    Text(stringResource(Res.string.notifications_help), Modifier.padding(top = AppSpacing.Large),
                        style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                }
            }
        }
    }
}

@Composable private fun InboxRow(item: InboxItem, fresh: Boolean, onClick: () -> Unit) {
    val freshLabel = stringResource(Res.string.notifications_new)
    // 초록 점은 눈으로만 보이므로 화면 리더에는 "새 알림"을 함께 읽힌다
    Row(Modifier.fillMaxWidth().clip(AppShapes.Card).clickable(role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { if (fresh) stateDescription = freshLabel }.padding(vertical = AppSpacing.Medium),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium), verticalAlignment = Alignment.Top) {
        // 이번에 처음 본 알림은 초록 점
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(if (fresh) AppColors.SelectionBorder else AppColors.Background))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title ?: item.body, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.title != null) Text(item.body, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            inboxTime(item.atMillis)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary) }
        }
    }
}

/** 오늘이면 "오후 3:05", 아니면 "11월 15일 오후 3:05"(한국 시간) */
@Composable private fun inboxTime(atMillis: Long): String? {
    val iso = Instant.fromEpochMilliseconds(atMillis).toString()
    val t = chatTimeParts(iso) ?: return null
    val clock = stringResource(if (t.afternoon) Res.string.chat_time_pm else Res.string.chat_time_am, t.hour12, t.minute.toString().padStart(2, '0'))
    return if (isSameKstDay(iso, Clock.System.now())) clock else stringResource(Res.string.chat_date_time, t.month, t.day, clock)
}
