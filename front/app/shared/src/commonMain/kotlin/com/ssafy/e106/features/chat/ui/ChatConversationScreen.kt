package com.ssafy.e106.features.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.distinctUntilChanged

/** 화면 표시만 담당한다. 서버 전송·읽음 ACK·타이머·자동 답장을 실행하지 않는다. */
@Composable
fun ChatConversationScreen(
    state: ChatConversationState,
    onDraftChange: (String) -> Unit,
    onAction: (ConversationAction) -> Unit,
    modifier: Modifier = Modifier,
    onMessageRecovery: (String, MessageRecoveryAction) -> Unit = { _, _ -> },
    conversationSupport: (@Composable () -> Unit)? = null,
    onMessagesDisplayed: (List<Long>) -> Unit = {},
    error: AppError? = null,   // 불러오기 실패 화면의 종류(연결 · 서버)를 고르는 데만 쓴다
    notices: (@Composable () -> Unit)? = null,   // 입력창 위(추천 위)에 붙는 아래 띠 자리. 전송 결과 확인 · 읽음 실패처럼 연결부가 아는 알림
) {
    val listState = rememberLazyListState()
    val latestDisplayed by rememberUpdatedState(onMessagesDisplayed)
    // 미리 구성된 오프스크린 항목은 읽음 처리하지 않는다. 실제 viewport에 들어온 상대 메시지만 전달한다.
    LaunchedEffect(state.readable, state.messages) {
        if (state.readable) snapshotFlow {
            val visibleKeys = listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
            state.messages.filter { !it.mine && it.delivery == MessageDelivery.Sent && "message-${it.id}" in visibleKeys }
                .mapNotNull { it.id.toLongOrNull() }
        }.distinctUntilChanged().collect { ids -> withFrameNanos { }; latestDisplayed(ids) }
    }
    Surface(modifier.fillMaxSize(), color = AppColors.Screen) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            // 가로 IME 등으로 남은 높이가 짧으면 입력/전송을 우선한다. 높이가 확보되면 제목/메뉴가 복귀한다.
            // 입력 컴포지션 위치는 유지하고 높이만 조정해 초안과 포커스를 보존한다.
            val compactInput = maxHeight < 240.dp
            Column(Modifier.fillMaxSize()) {
                if (!compactInput || !state.readable) ConversationTopBar(state, onAction)
                if (!state.readable) {
                    val leaving = state.load == ConversationLoad.Closed || state.load == ConversationLoad.Unavailable
                    val consent = state.load == ConversationLoad.ConsentRequired
                    // 다시 받을 수 있는 상태(불러오기 실패 · 권한 미확인)에서만 당기기와 작은 "다시 시도"를 둔다
                    val reload = { if (!leaving && !consent) onAction(ConversationAction.Reload) }
                    if (state.load == ConversationLoad.Loading) ConversationSkeleton(Modifier.weight(1f))
                    else PullDownRefreshBox(false, reload, Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                            verticalArrangement = Arrangement.Center) {
                            when (state.load) {
                                ConversationLoad.Closed -> StateView(StateKind.Locked, stringResource(Res.string.chat_conversation_closed))
                                ConversationLoad.ConsentRequired -> StateView(StateKind.Locked, stringResource(Res.string.chat_access_consent))
                                ConversationLoad.Unavailable -> StateView(StateKind.Locked, stringResource(Res.string.chat_access_unavailable))
                                ConversationLoad.Active -> StateView(StateKind.Locked, stringResource(Res.string.chat_access_unavailable), onRetry = reload)
                                else -> StateView(error.stateKind(), error.stateTitle(), onRetry = reload)
                            }
                        }
                    }
                    notices?.invoke()
                    // 갈 곳이 있는 상태는 아래 큰 버튼: 채팅 목록으로 / 동의 내용 확인하기
                    if (leaving || consent) AppButton(stringResource(if (consent) Res.string.chat_access_open_consent else Res.string.chat_conversation_back),
                        Modifier.widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                            .padding(horizontal = AppSpacing.Large).padding(top = AppSpacing.Small, bottom = AppSpacing.Large),
                        onClick = { onAction(if (consent) ConversationAction.OpenConsent else ConversationAction.Back) })
                } else {
                    // 입력창을 먼저 측정하고 남은 높이를 안내/대화에 나눈다. 큰 글자나 IME 때문에
                    // 안내가 길어져도 전송 버튼을 화면 밖으로 밀지 않고 안내 영역에서 스크롤한다.
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        // 여백을 남기는 가중치 대신 안내/추천 높이만 제한하고, 남는 높이는 대화 목록에 준다.
                        val noticeHeight = maxHeight * 0.35f
                        val supportHeight = minOf(240.dp, maxHeight * 0.5f)
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                                val messages = remember(state.messages) { state.messages.distinctBy { it.id } }
                                // 그날의 첫 메시지 id → (월, 일). 더 받을 이전 메시지가 남아 있으면 가장 오래된 메시지 위엔 두지 않는다
                                val days = remember(messages, state.nextCursor) { dayStarts(messages.map { it.id to it.timeLabel }, state.nextCursor.isNullOrBlank()) }
                                // 첫 대화: 가운데에 상대 첫 글자 · 안내
                                if (messages.isEmpty() && state.nextCursor.isNullOrBlank()) FirstConversation(state, onAction)
                                else LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth(), state = listState, reverseLayout = true,
                                    contentPadding = screenPaddingValues(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                                    if (messages.isEmpty()) item(key = "empty") { StateView(StateKind.Empty, stringResource(Res.string.chat_history_empty)) }
                                    // 아래(최신)부터 그린다. 날짜가 바뀌는 자리(그날의 첫 메시지 위)에 날짜 구분
                                    messages.asReversed().forEach { message ->
                                        item(key = "message-${message.id}") {
                                            MessageRow(message, state.inputEnabled) { action -> if (state.inputEnabled) onMessageRecovery(message.id, action) }
                                        }
                                        days[message.id]?.let { (month, day) -> item(key = "day-${message.id}") { DayDivider(month, day) } }
                                    }
                                    // 대화 맨 위(더 받을 이전 메시지가 없을 때): 종료 안내 · AI 분석 안내
                                    if (state.nextCursor.isNullOrBlank()) item(key = "head") { ConversationHead(onAction) }
                                    // 이전 메시지 이어 받기: 받는 중 = 말풍선 뼈대, 실패 = 한 줄 + 다시 시도, 그 밖엔 "이전 메시지 더 보기"(누를 때만 받는다)
                                    if (!state.nextCursor.isNullOrBlank()) item(key = "older-messages") {
                                        val older = { if (state.canLoadOlder) onAction(ConversationAction.LoadOlder) }
                                        when (state.historyLoad) {
                                            HistoryLoad.Loading -> ListTail(ListTailState.Loading) { BubbleBone(0.55f, false); BubbleBone(0.4f, true) }
                                            HistoryLoad.Failed -> ListTail(ListTailState.Failed, onRetry = older, retryEnabled = state.canLoadOlder)
                                            HistoryLoad.Idle -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                                TextAction(stringResource(Res.string.chat_history_more), older, enabled = state.canLoadOlder, icon = false)
                                            }
                                        }
                                    }
                                }
                            }
                            // 아래 띠(연결 끊김 · 전송 결과 확인 등)는 대화 아래, 추천 위에 둔다. 길어져도 대화 영역을 다 가리지 않게 높이를 제한한다
                            Column(Modifier.heightIn(max = noticeHeight).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                RealtimeNotice(state, onAction)
                                notices?.invoke()
                            }
                            // 추천은 말풍선 목록과 분리해 입력창 바로 위에 둔다. 작은 화면/키보드에서도
                            // 입력창 높이를 먼저 확보하고, 펼친 추천은 남은 영역 안에서 스크롤한다.
                            if (conversationSupport != null) Column(
                                Modifier.fillMaxWidth().heightIn(max = supportHeight)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = AppSpacing.Large),
                            ) { conversationSupport() }
                        }
                    }
                    ConversationComposer(state, onDraftChange, onAction, compactInput)
                }
            }
        }
    }
}

/** 위쪽: ‹ · 이름(누르면 상대 정보)과 종료 시각 · ⋯(차단 · 신고 메뉴). 읽을 수 없는 상태에선 ‹ 와 제목만 */
@Composable
private fun ConversationTopBar(state: ChatConversationState, onAction: (ConversationAction) -> Unit) {
    val back = stringResource(Res.string.button_previous)
    val menu = stringResource(Res.string.chat_conversation_menu)
    val partner = stringResource(Res.string.chat_partner_open)
    Row(Modifier.fillMaxWidth().heightIn(min = AppSizes.TopBarMinHeight).padding(horizontal = AppSpacing.Small),
        verticalAlignment = Alignment.CenterVertically) {
        // 눌러도 회색 원이 생기지 않게 표시 없이, 터치 영역은 48dp
        Box(Modifier.size(48.dp).clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button,
            onClick = { onAction(ConversationAction.Back) }), contentAlignment = Alignment.Center) {
            Icon(painterResource(Res.drawable.ic_arrow_back), back, tint = AppColors.TextPrimary)
        }
        // 제목(heading)은 포커스를 받는 이 영역에 둔다
        Column(Modifier.weight(1f).heightIn(min = 48.dp).clip(AppShapes.Small).semantics(mergeDescendants = true) { heading() }
            .then(if (state.readable) Modifier.clickable(role = Role.Button, onClickLabel = partner) { onAction(ConversationAction.OpenPartner) } else Modifier)
            .padding(horizontal = AppSpacing.Small), verticalArrangement = Arrangement.Center) {
            Text(if (state.readable) state.nickname?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.chat_conversation_title)
                else stringResource(Res.string.chat_conversation_title),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.readable) closesText(state.closesAtLabel)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (state.readable) Box(Modifier.size(48.dp).clip(CircleShape).semantics { contentDescription = menu }
            .clickable(role = Role.Button) { onAction(ConversationAction.OpenMenu) }, contentAlignment = Alignment.Center) {
            Text("⋯", style = MaterialTheme.typography.titleLarge, color = AppColors.TextSecondary, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/** "11월 18일 오후 3:00 종료". 서버 시각을 못 읽으면 받은 글자 그대로, 없으면 표시하지 않는다 */
@Composable
private fun closesText(label: String?): String? {
    val raw = label?.takeIf { it.isNotBlank() } ?: return null
    val t = chatTimeParts(raw) ?: return stringResource(Res.string.chat_conversation_closes, raw)
    return stringResource(Res.string.chat_conversation_closes, stringResource(Res.string.chat_date_time, t.month, t.day, clock(t)))
}

@Composable
private fun clock(t: ChatTimeParts) = stringResource(if (t.afternoon) Res.string.chat_time_pm else Res.string.chat_time_am,
    t.hour12, t.minute.toString().padStart(2, '0'))

/** 말풍선 한 줄: 내 것은 오른쪽 검정, 상대는 왼쪽 회색. 시각은 아래 작은 글씨, 내 메시지는 전송 상태 */
@Composable
private fun MessageRow(message: ConversationMessage, enabled: Boolean, onRecovery: (MessageRecoveryAction) -> Unit) {
    val who = stringResource(if (message.mine) Res.string.chat_conversation_me else Res.string.chat_conversation_other)
    val sent = message.delivery == MessageDelivery.Sent
    val side = if (message.mine) Alignment.End else Alignment.Start
    Column(Modifier.fillMaxWidth(), horizontalAlignment = side, verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
        // 화면 리더는 "나/상대 · 본문 · 시각"을 한 번에 읽는다
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = who }, horizontalAlignment = side,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
            Surface(Modifier.widthIn(max = 286.dp), shape = RoundedCornerShape(16.dp),
                color = if (message.mine) AppColors.ActionPrimary.copy(alpha = if (sent) 1f else 0.6f) else AppColors.SurfaceMuted) {
                Text(message.text, Modifier.padding(horizontal = AppSpacing.Large, vertical = AppSpacing.Medium),
                    style = MaterialTheme.typography.bodyMedium, color = if (message.mine) AppColors.OnPrimary else AppColors.TextPrimary)
            }
            if (sent) Text(chatTimeParts(message.timeLabel)?.let { clock(it) } ?: message.timeLabel?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.chat_conversation_time_unknown),
                style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (message.mine) MessageDeliveryStatus(message, enabled = enabled, onRecovery = onRecovery)
    }
}

@Composable
private fun DayDivider(month: Int, day: Int) {
    Box(Modifier.fillMaxWidth().padding(vertical = AppSpacing.Small), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = AppColors.SurfaceMuted) {
            Text(stringResource(Res.string.chat_date, month, day), Modifier.padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.ExtraSmall),
                style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
        }
    }
}

/** 대화 맨 위 안내: 종료되면 다시 볼 수 없음 · AI 분석 안내 */
@Composable
private fun ConversationHead(onAction: (ConversationAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = AppSpacing.Small), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(Res.string.chat_conversation_notice), style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary, textAlign = TextAlign.Center)
        TextButton(onClick = { onAction(ConversationAction.OpenAiNotice) }) {
            Text(stringResource(Res.string.chat_conversation_ai_notice), style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
        }
    }
}

/** 첫 대화: 가운데 상대 첫 글자 원 · "○○님과 연결됐어요" · 종료 안내 */
@Composable
private fun FirstConversation(state: ChatConversationState, onAction: (ConversationAction) -> Unit) {
    val name = state.nickname?.takeIf { it.isNotBlank() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium, Alignment.CenterVertically)) {
        Box(Modifier.size(AppSizes.Avatar).clip(CircleShape).background(AppColors.SurfaceMuted), contentAlignment = Alignment.Center) {
            Text(name.initial(), style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextSecondary, modifier = Modifier.clearAndSetSemantics {})
        }
        Text(if (name != null) stringResource(Res.string.chat_conversation_connected, name) else stringResource(Res.string.chat_conversation_empty),
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(Res.string.chat_conversation_empty_help), style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary, textAlign = TextAlign.Center)
        ConversationHead(onAction)
    }
}

/** 수신 연결에 문제가 있을 때만 아래 띠 한 줄(연결 중 · 연결됨은 표시하지 않는다). 멈췄으면 "다시 시도"로 다시 연결 */
@Composable
private fun RealtimeNotice(state: ChatConversationState, onAction: (ConversationAction) -> Unit) {
    when (state.realtime) {
        ChatRealtimeStatus.Reconnecting -> NoticeBar(stringResource(Res.string.chat_realtime_reconnecting), actionText = null)
        ChatRealtimeStatus.Stopped -> NoticeBar(stringResource(Res.string.chat_realtime_stopped), onAction = { onAction(ConversationAction.Reload) })
        else -> Unit
    }
}

/** 뼈대: 대화를 받는 동안 말풍선 자리(상대는 왼쪽, 나는 오른쪽) */
@Composable
private fun ConversationSkeleton(modifier: Modifier = Modifier) {
    SkeletonGroup(modifier.padding(screenPaddingValues()), spacing = AppSpacing.Medium) {
        listOf(0.6f to false, 0.4f to true, 0.7f to false, 0.5f to true, 0.35f to true).forEach { (width, mine) -> BubbleBone(width, mine) }
    }
}

@Composable
private fun BubbleBone(fraction: Float, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        SkeletonBone(Modifier.fillMaxWidth(fraction), height = 44.dp, shape = RoundedCornerShape(16.dp))
    }
}

@Composable
private fun ConversationComposer(state: ChatConversationState, onDraftChange: (String) -> Unit,
    onAction: (ConversationAction) -> Unit, compactInput: Boolean) {
    val validation = state.validatedDraft
    val inputError = validation.error?.takeIf { it != ChatDraftError.Blank }
    HorizontalDivider(color = AppColors.BorderSubtle)
    Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Large, vertical = AppSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
        if (!state.canSend) Text(stringResource(Res.string.chat_conversation_read_only),
            style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        // 입력/전송을 같은 행에서 측정해 IME가 높이를 줄여도 버튼이 입력창 아래에서 잘리지 않게 한다.
        // 키보드 개폐 전후에 같은 Row를 유지하므로 입력창을 다른 부모로 옮기며 포커스를 잃지 않는다.
        val inputLabel = stringResource(Res.string.chat_conversation_input)
        val sendLabel = stringResource(Res.string.chat_conversation_send)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            // 여러 줄 입력은 이 기능의 UI이며 기존 한 줄 AppTextField 계약을 변경하지 않는다.
            // 짧은 창에서는 입력 내부를 스크롤한다. 줄바꿈/본문 자체를 제거하거나 전송 규칙을 바꾸지 않는다.
            BasicTextField(value = state.draft, onValueChange = { if (state.inputEnabled) onDraftChange(it) },
                enabled = state.inputEnabled, modifier = Modifier.weight(1f).semantics { contentDescription = inputLabel },
                minLines = 1, maxLines = if (compactInput) 1 else 3,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = AppColors.TextPrimary),
                cursorBrush = SolidColor(AppColors.TextPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                decorationBox = { field ->
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(22.dp))
                        .background(if (inputError != null) AppColors.Error.copy(alpha = 0.08f) else AppColors.SurfaceMuted)
                        .padding(horizontal = AppSpacing.Large, vertical = AppSpacing.Medium), contentAlignment = Alignment.CenterStart) {
                        if (state.draft.isEmpty()) Text(stringResource(Res.string.chat_conversation_placeholder),
                            style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary)
                        field()
                    }
                })
            // 동그란 보내기(→). 화면 리더엔 "전송"
            // 터치 영역은 48dp, 보이는 원은 44dp
            Box(Modifier.size(48.dp).clip(CircleShape)
                .semantics { contentDescription = sendLabel }
                .clickable(enabled = state.submitEnabled, role = Role.Button) { if (state.submitEnabled) onAction(ConversationAction.Send) },
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(44.dp).clip(CircleShape)
                    .background(if (state.submitEnabled || state.submitting) AppColors.ActionPrimary else AppColors.BorderSubtle),
                    contentAlignment = Alignment.Center) {
                    if (state.submitting) CircularProgressIndicator(Modifier.size(18.dp), color = AppColors.OnPrimary, strokeWidth = 2.dp)
                    else Text("→", style = MaterialTheme.typography.titleMedium, color = AppColors.OnPrimary, modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
        // 글자 수는 한도에 가까워지거나 넘었을 때만
        if (validation.graphemes >= 900 || inputError != null)
        Text(stringResource(Res.string.chat_conversation_count, validation.graphemes),
            style = MaterialTheme.typography.bodySmall,
            color = if (inputError != null) AppColors.Error else AppColors.TextSecondary)
        if (inputError != null) Text(stringResource(when (inputError) {
            ChatDraftError.Bytes -> Res.string.chat_input_bytes
            ChatDraftError.InvalidText -> Res.string.chat_input_invalid
            else -> Res.string.chat_conversation_too_long
        }),
            style = MaterialTheme.typography.bodySmall, color = AppColors.Error)
    }
}
