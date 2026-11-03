package com.ssafy.e106.features.chat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.features.chat.data.chatCode
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlinx.coroutines.delay

/** 화면 이동은 서버의 접근 허용을 대신하지 않는다. 방은 진입/복귀마다 ViewModel에서 권한부터 확인한다. */
@Composable fun ConnectedChatRoute(session: Session.SignedIn, initialRoom: Long? = null,
    onHome: () -> Unit, onProposals: () -> Unit, onProfile: () -> Unit,
    onSafety: (Long, String) -> Unit, onConsent: () -> Unit,
    support: (@Composable (Long, Boolean, Boolean, (String) -> Unit) -> Unit)? = null,
    listModel: ChatListViewModel = koinViewModel(), roomModel: ChatRoomViewModel = koinViewModel(),
    partnerModel: ChatPartnerViewModel = koinViewModel()) {
    var room by rememberSaveable(session.userId, initialRoom) { mutableStateOf(initialRoom) }
    var page by rememberSaveable(session.userId, initialRoom) { mutableStateOf("conversation") }
    val list by listModel.state.collectAsStateWithLifecycle()
    val conversation by roomModel.state.collectAsStateWithLifecycle()
    val partner by partnerModel.state.collectAsStateWithLifecycle()
    var displayed by remember(room) { mutableStateOf(emptyList<Long>()) }
    fun load() { if (room == null) listModel.refresh() else when(page) {
        "partner" -> partnerModel.load(room!!)
        else -> roomModel.open(room!!)
    } }
    fun back() { if (conversation.sending) return
        if (page != "conversation") { partnerModel.clear(); page = "conversation" }
        else if (room != null) { roomModel.pause(); room = null } else onHome()
    }
    // 메뉴는 같은 대화방 안의 표시 전환이다. 메뉴 진입·복귀로 방을 다시 열어 저장 전 초안을 덮어쓰지 않는다.
    // 상대 프로필은 별도 조회 화면이므로 그 화면을 오갈 때만 방/프로필 진입 처리를 다시 수행한다.
    LaunchedEffect(session, room, page == "partner") { load() }
    // 화면 상태를 유지하며 권한과 공개 필드를 확인한다. 변경 없는 사진은 다시 다운로드하지 않는다.
    LaunchedEffect(session, room, page, partner.scope, partner.visible) {
        if (page == "partner" && room != null && partner.visible) {
            while (true) { delay(5_000); partnerModel.checkAccess() }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { load() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { listModel.pause(); roomModel.pause(); partnerModel.clear() }
    DisposableEffect(Unit) { onDispose { listModel.pause(); roomModel.pause(); partnerModel.clear() } }
    ProfileBackHandler(enabled = true) { back() }
    if (room == null) {
        if (list.owner != session) return
        Column {
            list.error?.let { Text(it.message, Modifier.padding(AppSpacing.Medium), color = MaterialTheme.colorScheme.error) }
            // 목록 조회부터 동의가 필요할 수 있으므로 방에 들어가지 않아도 동의 관리로 이동할 수 있다.
            if (list.error?.chatCode() == "AI_RISK_CONSENT_REQUIRED") {
                AppButton(stringResource(Res.string.chat_access_open_consent),
                    Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Medium),
                    onClick = { listModel.pause(); onConsent() })
            }
            ChatListScreen(list.ui, { room = it; page = "conversation" }, { action -> when(action) {
                ChatListAction.Refresh -> listModel.refresh()
                ChatListAction.LoadMore -> listModel.more()
                ChatListAction.Home -> onHome()
                ChatListAction.Proposals -> onProposals()
                ChatListAction.Profile -> onProfile()
            } })
        }; return
    }
    if (page == "partner") {
        ChatPartnerScreen(partner, onAction = { action -> when(action) {
            ChatPartnerAction.Reload, ChatPartnerAction.RetryPhoto -> partnerModel.load(room!!)
            ChatPartnerAction.ChatList -> { partnerModel.clear(); page = "conversation"; room = null }
            else -> back()
        } }); return
    }
    if (conversation.owner != session || conversation.id != room) return
    if (page == "menu" && conversation.ui.readable) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(AppSpacing.Large), verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            AppTopBar(stringResource(Res.string.chat_connected_menu), onBack = { back() })
            for ((operation, label) in listOf("REPORT" to Res.string.chat_connected_report,
                "BLOCK" to Res.string.chat_connected_block, "LEAVE" to Res.string.chat_connected_leave)) {
                AppButton(stringResource(label), Modifier.fillMaxWidth(), enabled = !conversation.sending,
                    onClick = { roomModel.pause(); onSafety(room!!, operation) })
            }
        }; return
    }
    Column(Modifier.fillMaxSize()) {
        conversation.error?.let { Text(it.message, Modifier.padding(AppSpacing.Medium), color = MaterialTheme.colorScheme.error) }
        conversation.pending?.let { pending ->
            if (!conversation.sending) {
                Text(stringResource(if (pending.room == room) Res.string.chat_connected_recovery else Res.string.chat_connected_other_pending), Modifier.padding(AppSpacing.Medium))
                AppButton(stringResource(if (pending.room == room) Res.string.chat_connected_check else Res.string.chat_connected_open_pending),
                    enabled = pending.room != room || conversation.ui.readable && conversation.ui.canSend,
                    onClick = { if (pending.room == room) roomModel.retry() else room = pending.room })
            }
        }
        if (conversation.readError) TextButton(onClick = { roomModel.displayed(displayed) }) { Text(stringResource(Res.string.chat_connected_read_failed)) }
        ChatConversationScreen(conversation.ui, roomModel::draft, { action -> when(action) {
            ConversationAction.Back -> back()
            ConversationAction.Reload -> roomModel.reload()
            ConversationAction.Send -> roomModel.send()
            ConversationAction.LoadOlder -> roomModel.older()
            ConversationAction.OpenPartner -> { roomModel.pause(); page = "partner" }
            ConversationAction.OpenMenu -> page = "menu"
            ConversationAction.OpenAiNotice, ConversationAction.OpenConsent -> { roomModel.pause(); onConsent() }
        } }, onMessageRecovery = { _, _ -> roomModel.retry() },
            onMessagesDisplayed = { displayed = it; roomModel.displayed(it) },
            conversationSupport = support?.let { block -> { block(room!!, conversation.ui.readable, conversation.ui.inputEnabled, roomModel::draft) } })
    }
}
