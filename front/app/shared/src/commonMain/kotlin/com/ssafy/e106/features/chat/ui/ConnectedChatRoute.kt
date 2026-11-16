package com.ssafy.e106.features.chat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
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
    // 현재 초안을 추천 영역에 전달해 비어 있을 때만 확인 없이 채운다.
    // 방 번호 · 읽기 가능 · 입력 가능 · 초안 · 초안 넣기 · 차단 화면 열기
    // 마지막 인자: 화면에 있는 메시지 본문(보낸 추천 질문을 목록에서 빼는 데 쓴다). 첫 페이지를 받기 전엔 null
    support: (@Composable (Long, Boolean, Boolean, String, (String) -> Unit, () -> Unit, String?, Set<String>?) -> Unit)? = null,
    listModel: ChatListViewModel = koinViewModel(), roomModel: ChatRoomViewModel = koinViewModel(),
    partnerModel: ChatPartnerViewModel = koinViewModel(),
    // 대화 화면이 보이는 동안의 방 번호(안 보이면 null). 그 방의 새 메시지 알림을 띄우지 않는 데 쓴다
    onViewing: (Long?) -> Unit = {}) {
    var room by rememberSaveable(session.userId, initialRoom) { mutableStateOf(initialRoom) }
    var page by rememberSaveable(session.userId, initialRoom) { mutableStateOf("conversation") }
    val list by listModel.state.collectAsStateWithLifecycle()
    val conversation by roomModel.state.collectAsStateWithLifecycle()
    val partner by partnerModel.state.collectAsStateWithLifecycle()
    var displayed by remember(room) { mutableStateOf(emptyList<Long>()) }
    // 목록에서 누른 방의 상대 닉네임. 방에 들어가면 목록이 비워지므로 누를 때 받아 둔다
    var entry by remember { mutableStateOf<Pair<Long, String?>?>(null) }
    fun load() { if (room == null) listModel.refresh() else when(page) {
        "partner" -> partnerModel.load(room!!)
        else -> roomModel.open(room!!, entry?.takeIf { it.first == room }?.second)
    } }
    fun back() { if (conversation.sending) return
        if (page != "conversation") { partnerModel.clear(); page = "conversation" }
        // 읽을 수 없는(닫힌) 방에서 나가면 닉네임도 지운다. 다시 들어올 때 옛 이름이 보이지 않게
        else if (room != null) { if (conversation.ui.readable) roomModel.pause() else roomModel.leave(); room = null } else onHome()
    }
    // 메뉴는 같은 대화방 안의 표시 전환이다. 메뉴 진입·복귀로 방을 다시 열어 저장 전 초안을 덮어쓰지 않는다.
    // 상대 프로필은 별도 조회 화면이므로 그 화면을 오갈 때만 방/프로필 진입 처리를 다시 수행한다.
    LaunchedEffect(session, room, page == "partner") { load() }
    // 앱이 뒤로 가거나 상대 프로필 · 목록으로 나가면 다시 알림을 받는다
    LifecycleResumeEffect(room, page) { onViewing(room.takeIf { page == "conversation" }); onPauseOrDispose { onViewing(null) } }
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
        // 오류는 화면 위 빨간 글 대신 목록의 상태 화면 · 아래 띠가 알린다.
        // 목록 조회부터 동의가 필요할 수 있으므로 방에 들어가지 않아도 동의 관리로 이동할 수 있다.
        ChatListScreen(list.ui, { id -> entry = id to list.ui.rooms.firstOrNull { it.chatRoomId == id }?.nickname
            room = id; page = "conversation" }, { action -> when(action) {
            ChatListAction.Refresh -> listModel.refresh()
            ChatListAction.LoadMore -> listModel.more()
            ChatListAction.Home -> onHome()
            ChatListAction.Proposals -> onProposals()
            ChatListAction.Profile -> onProfile()
        } }, error = list.error,
            onConsent = if (list.error?.chatCode() == "AI_RISK_CONSENT_REQUIRED") ({ listModel.pause(); onConsent() }) else null)
        return
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
        // 가로 화면 · 큰 글자에서도 아래 버튼까지 닿게 스크롤
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(AppSpacing.Large), verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            AppTopBar(stringResource(Res.string.chat_connected_menu), onBack = { back() })
            for ((operation, label) in listOf("REPORT" to Res.string.chat_connected_report,
                "BLOCK" to Res.string.chat_connected_block, "LEAVE" to Res.string.chat_connected_leave)) {
                AppButton(stringResource(label), Modifier.fillMaxWidth(), enabled = !conversation.sending,
                    onClick = { roomModel.leave(); onSafety(room!!, operation) })
            }
            // 대화 맨 위 안내는 이전 메시지를 다 받아야 보이므로 메뉴에서도 열 수 있게 한다
            AppButton(stringResource(Res.string.chat_conversation_ai_notice), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                onClick = { roomModel.pause(); onConsent() })
        }; return
    }
    // 결과를 모르는 전송은 다시 보내지 않고 같은 메시지로 결과부터 확인한다(안내 문구는 그대로 둔다)
    val pending = conversation.pending?.takeIf { !conversation.sending }
    // 보낸 추천 질문을 거르는 본문 모음. 입력할 때마다 다시 만들지 않게 메시지가 바뀔 때만 계산한다
    val usedTexts = remember(conversation.ui.messages, conversation.ui.firstPageLoaded) { usedMessageTexts(conversation.ui) }
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
        conversationSupport = support?.let { block -> { block(room!!, conversation.ui.readable, conversation.ui.inputEnabled, conversation.ui.draft, roomModel::draft,
            { if (!conversation.sending) { roomModel.leave(); onSafety(room!!, "BLOCK") } }, conversation.ui.messages.lastOrNull()?.id,
            usedTexts) } },
        // 화면 위 빨간 글 · 버튼 대신 입력창 위 아래 띠로 알린다. 불러오기 실패는 화면이 상태 화면으로 보여 준다
        error = conversation.error, notices = {
            pending?.let { sent ->
                val here = sent.room == room
                NoticeBar(stringResource(if (here) Res.string.chat_connected_recovery else Res.string.chat_connected_other_pending),
                    actionText = stringResource(if (here) Res.string.chat_connected_check else Res.string.chat_connected_open_pending),
                    actionEnabled = !here || conversation.ui.readable && conversation.ui.canSend,
                    onAction = { if (here) roomModel.retry() else room = sent.room })
            }
            if (conversation.readError) NoticeBar(stringResource(Res.string.chat_connected_read_failed), onAction = { roomModel.displayed(displayed) })
            // 보던 대화는 그대로 두고 한 줄만(이전 메시지 실패는 목록 끝 줄이, 전송 실패는 말풍선이 따로 알린다)
            if (conversation.ui.readable && pending == null && conversation.ui.historyLoad != HistoryLoad.Failed)
                conversation.error?.let { NoticeBar(it.stateTitle(), actionText = null) }
        })
}
